package cn.edu.jxnu.seckill.controller

import java.util.{HashMap => JavaHashMap}

import cn.edu.jxnu.seckill.access.AccessLimit
import cn.edu.jxnu.seckill.domain.SeckillUser
import cn.edu.jxnu.seckill.rabbitmq.{RabbitMQSender, SeckillMessage}
import cn.edu.jxnu.seckill.redis.RedisService
import cn.edu.jxnu.seckill.redis.key.{GoodsKey, OrderKey, SeckillKey}
import cn.edu.jxnu.seckill.result.{CodeMsg, Result}
import cn.edu.jxnu.seckill.service.{GoodsService, OrderService, SeckillService}
import io.swagger.annotations.{Api, ApiOperation}
import javax.imageio.ImageIO
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.ui.Model
import org.springframework.web.bind.annotation._

import scala.collection.JavaConversions._
import scala.collection.immutable.{HashMap => _, Map => _}
import scala.language.implicitConversions

/**
 * 秒杀控制器
 * 
 * 初始化bean
 *
 * @author 梦境迷离.
 * @time 2018年5月21日
 * @version v1.0
 */
@RestController
@RequestMapping(Array("/seckill"))
@Api(value = "秒杀controller", tags = { Array("秒杀接口") })
class SeckillController @Autowired() (goodsService: GoodsService, seckillService: SeckillService,
    orderService: OrderService, redisService: RedisService,
    sender: RabbitMQSender) extends InitializingBean {

    private final val log = LoggerFactory.getLogger(classOf[SeckillController])
    private val localOverMap = new JavaHashMap[Long, Boolean]()

    /**
     * 获取秒杀路径，返回给前端
     *
     * defaultValue=0 测试限流用，实际不能有默认值
     */
    @AccessLimit(seconds   = 5, maxCount = 5, needLogin = true)
    @GetMapping(Array("/path"))
    def getMiaoshaPath(request: HttpServletRequest, user: SeckillUser, @RequestParam("goodsId") goodsId: Long,
        @RequestParam(value = "verifyCode", defaultValue = "0") verifyCode: Integer): Result[String] = {

        if (user == null)
            return Result.error(CodeMsg.SESSION_ERROR)
        val check = seckillService.checkVerifyCode(user, goodsId, verifyCode)
        if (!check)
            return Result.error(CodeMsg.REQUEST_ILLEGAL)
        Result.success(seckillService.createSeckillPath(user, goodsId))
    }

    /**
     * 真正秒杀的接口，需要一个@PathVariable参数
     */
    @PostMapping(Array("/{path}/do_seckill"))
    def seckill(model: Model, user: SeckillUser, @RequestParam("goodsId") goodsId: Long,
        @PathVariable("path") path: String): Result[Integer] = {

        model.addAttribute("user", user)
        if (user == null)
            return Result.error(CodeMsg.SESSION_ERROR)
        // 验证path
        val check = seckillService.checkPath(user, goodsId, path)
        if (!check)
            return Result.error(CodeMsg.REQUEST_ILLEGAL)
        // 内存标记，减少redis访问
        val over = localOverMap.get(goodsId)
        if (over)
            return Result.error(CodeMsg.SECKILL_OVER)
        // 尝试性的减小库存【预减库存】
        val stock = redisService.decr(GoodsKey.getSeckillGoodsStock, "" + goodsId) // 10
        if (stock < 0) {
            localOverMap.put(goodsId, true)
            return Result.error(CodeMsg.SECKILL_OVER)
        }
        // 判断是否已经秒杀到了
        val order = orderService.getSeckillOrderByUserIdGoodsId(user.getId(), goodsId)
        if (order != null)
            return Result.error(CodeMsg.REPEATE_SECKILL)
        // 入队
        val mm = new SeckillMessage()
        mm.setUser(user)
        mm.setGoodsId(goodsId)
        sender.sendSeckillMessage(mm)
        Result.success(0) // 排队中
        /*
		 * //判断库存 GoodsVo goods = goodsService.getGoodsVoByGoodsId(goodsId) int stock =
		 * goods.getStockCount() if(stock <= 0) { return
		 * Result.error(CodeMsg.SECKILL_OVER) } //判断是否已经秒杀到了 SeckillOrder order =
		 * orderService.getSeckillOrderByUserIdGoodsId(user.getId(), goodsId) if(order
		 * != null) { model.addAttribute("errmsg", CodeMsg.REPEATE_SECKILL.getMsg())
		 * return Result.error(CodeMsg.REPEATE_SECKILL) } //减库存 下订单 写入秒杀订单 OrderInfo
		 * orderInfo = seckillService.seckill(user, goods) return
		 * Result.success(orderInfo)
		 */
    }

    /**
     * orderId：成功 -1：秒杀失败 0： 排队中
     */
    @GetMapping(Array("/result"))
    def miaoshaResult(model: Model, user: SeckillUser, @RequestParam("goodsId") goodsId: Long): Result[Long] = {

        model.addAttribute("user", user)
        if (user == null)
            return Result.error(CodeMsg.SESSION_ERROR)
        //从数据库去查询订单，存在测可以得到订单ID
        Result.success(seckillService.getSeckillResult(user.getId(), goodsId))
    }

    /**
     * 系统初始化加载商品库存到redis中
     */
    override def afterPropertiesSet() {

        val goodsList = goodsService.listGoodsVo()
        if (goodsList == null)
            return
        for (goods <- goodsList) {
            redisService.set(GoodsKey.getSeckillGoodsStock, "" + goods.getId(), goods.getStockCount())
            localOverMap.put(goods.getId(), false) //内存标记为没有秒杀完
        }
    }

    /**
     * 数据还原【测试用】
     */
    @ApiOperation(value = "数据还原", notes = { "数据还原" })
    @GetMapping(Array("/reset"))
    def reset(model: Model): Result[Boolean] = {

        val goodsList = goodsService.listGoodsVo()
        for (goods <- goodsList) {
            goods.setStockCount(10)
            // 设置初始的容量
            redisService.set(GoodsKey.getSeckillGoodsStock, "" + goods.getId(), 10)
            localOverMap.put(goods.getId(), false)
        }
        redisService.delete(OrderKey.getSeckillOrderByUidGid)
        redisService.delete(SeckillKey.isGoodsOver)
        seckillService.reset(goodsList)
        Result.success(true)
    }

    /**
     * 生成验证码接口
     */
    @GetMapping(Array("/verifyCode"))
    def getMiaoshaVerifyCod(response: HttpServletResponse, user: SeckillUser,
        @RequestParam("goodsId") goodsId: Long): Result[String] = {

        if (user == null)
            return Result.error(CodeMsg.SESSION_ERROR)
        try {
            val image = seckillService.createVerifyCode(user, goodsId)
            val out = response.getOutputStream()
            ImageIO.write(image, "JPEG", out)
            out.flush()
            out.close()
            return null
        } catch {
            case e: Exception =>
                e.printStackTrace()
                Result.error(CodeMsg.SECKILL_FAIL)
        }
    }

}