package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RabbitTemplate rabbitTemplate;
    private IVoucherOrderService proxy;

    //同步秒杀思路
    public Result synSeckillVoucher(Long voucherId) {
        //查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //判断秒杀活动是否正在进行
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始！");
        }
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束！");
        }

        //判断库存是否充足
        if(voucher.getStock() < 1){
            return Result.fail("库存不足！");
        }

        //通过这个用户的ID查询他下单的数量，实现一人一单
        Long userID = UserHolder.getUser().getId();

        //创建锁的对象（Redis分布式锁）
        /*
        分布式锁不仅解决集群模式下的并发问题，也解决了一人一单的问题
        关键在于创建锁对象时传入的这个参数："order:" + userID
        这代表无论两个请求是来自同一台服务器还是不同服务器
        最终都会去 Redis 尝试对同一个 Key "order:100" 执行 SET NX（set if not exists）操作
         */
        SimpleRedisLock redisLock = new SimpleRedisLock(stringRedisTemplate, "order:" + userID);

        //redisson的自动锁
        //RLock redissonLock = redissonClient.getLock("lock:order:" + userID);
        //boolean redissonIsLock = redissonLock.tryLock();

        //这里有两个锁，一个乐观锁一个悲观锁实现出票功能
        //悲观锁同时也是分布式锁，只锁相同用户
        boolean isLock = redisLock.tryLock(/*userID.toString().intern()*/1000L);

        if(!isLock){
            //获取锁失败，只有可能是数据库里面已经有这个人的订单了
            return Result.fail("该优惠券每个用户只能购买一张！");
        }
        try{
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();

            /*
            解释一下上面的代码：
            当你给一个函数加上@Transactional注解时，Spring并不是直接调用你的这个方法，它会为你创建一个该类的代理对象
            当外部代码调用这个方法时，实际上是调用了代理对象的同名方法

            如果不使用 proxy，说明调用时采取的是 this.createVoucherOrder()
            这是一个对象内部的方法调用，它不会经过 Spring 的代理对象，而是直接调用了原始对象的createVoucherOrder方法
            因此，代理对象中那些“开启事务”、“提交/回滚事务”的逻辑就完全被绕过了，@Transactional 注解也就失效了

            为了解决这个问题，我们需要想办法调用到那个代理对象的方法，而不是原始对象的方法。
            AopContext.currentProxy() 就是 Spring 提供的一个工具，它可以获取到当前正在被调用的那个代理对象。

             */

            Long userId = UserHolder.getUser().getId();
            Long orderId = redisIdWorker.nextId("order");
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);

            proxy.createVoucherOrder(voucherOrder);
            return Result.ok(voucherOrder);

        }finally {
            redisLock.unlock();
        }
        /*
        为什么synchronized（现在是分布式）锁要加在这里呢，是因为下面方法的写操作会开启事务
        事务的核心特性之一就是Isolation(隔离性)，有点类似线程，在没提交之前都是相互隔离的，所以必须先拿锁再开启事务
        */

    }

    //异步秒杀思路
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        if(result.intValue() != 0){
            return Result.fail(result.intValue() == 1? "优惠券库存不足！":"该优惠券每个用户只能购买一张！");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        // 放入阻塞队列
        // orderTasks.add(voucherOrder);

        // 放入消息队列RabbitMQ
        // 序列化订单对象，发送到交换机
        try {
            // 放入消息队列RabbitMQ
            rabbitTemplate.convertAndSend(
                    "seckillOrder.direct",
                    "seckillOrder",
                    voucherOrder
            );
            log.info("消息发送成功，订单ID：{}", orderId); // 新增日志
        } catch (Exception e) {
            log.error("消息发送失败，订单ID：{}", orderId, e); // 捕获发送异常
            return Result.fail("创建订单失败，请重试"); // 告知前端失败
        }

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }


    //执行lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    // 阻塞队列思路
    /*
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    // 当线程试图从里面取出数据时，如果队列为空就会被阻塞，直到有元素再被唤醒

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    // 创建一个“单线程”的线程池，为什么只用一个线程？
    // 因为你的目的是保护数据库！如果你在这里用100个线程，那这100个线程就会并发地从队列里拿订单，然后并发地去轰炸数据库
    // 这和你最开始的同步方案就没区别了，数据库一样会死。
    
    @PostConstruct
    // 这个注解表示在VoucherOrderServiceImpl类被创建、所有依赖注入都完成后，自动执行一次init()方法。
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while(true){
                try{
                    //获取队列中的订单信息，如果队列里有订单，它就拿走一个
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }
     */


    // RabbitMQ 消息队列
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = "seckillOrder.queue",
                    arguments = {
                            @Argument(name = "x-dead-letter-exchange", value = "seckillOrder.dlx.direct"),
                            @Argument(name = "x-dead-letter-routing-key", value = "seckillOrder.fail")
                    }
            ),
            exchange = @Exchange(name = "seckillOrder.direct", type = ExchangeTypes.DIRECT),
            key = {"seckillOrder"}
    ))
    public void listenVoucherStockReduce(VoucherOrder voucherOrder){

        try {
            if (voucherOrder == null) {
                log.error("订单消息反序列化失败，无法解析为VoucherOrder");
                return;
            }
            handleVoucherOrder(voucherOrder);
        } catch (Exception e) {
            log.error("订单处理失败", e);
            // 可选：处理失败时，可将消息重试或转移到死信队列
            /*
            if (voucherOrder != null) {
                rabbitTemplate.convertAndSend(
                        "seckillOrder.dlx.direct",
                        "seckillOrder.fail",
                        voucherOrder
                );
            }else {
                log.error("订单消息反序列化失败，无法发送到死信队列");
            }
            */
        }
    }

    // 手动操作死信队列
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "seckillOrder.dlx.queue"),
            exchange = @Exchange(name = "seckillOrder.dlx.direct", type = ExchangeTypes.DIRECT),
            key = {"seckillOrder.fail"}
    ))
    public void listenVoucherStickReduceFAIL(VoucherOrder voucherOrder){
        if(voucherOrder == null){
            log.error("订单消息在传入死信队列后反序列化失败");
            return;
        }
        log.error("出现失败订单，已进入死信队列准备人工处理，订单ID：{}", voucherOrder.getId());
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户和锁
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();

        if(!isLock){
            log.error("不允许重复下单！");
            return;
        }

        try{
            proxy.createVoucherOrder(voucherOrder);
        }finally{
            lock.unlock();
        }
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){

        //通过这个用户的ID查询他下单的数量，实现一人一单
        Long userID = voucherOrder.getUserId();
        //数据库检查一下
        Long count = query().eq("user_id", userID).eq("voucher_id", voucherOrder).count();
        if(count > 0){
            //这个用户下过这张优惠券的单
            log.error("用户只能购买一次！");
            return;
        }

        //扣减库存数
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                // 乐观锁解决超卖问题
                /*

                黑马程序员的第一个版本是只有“当前数据库中的voucher数据”和“查询时记录的voucher数据”一致时，才允许扣减
                即.eq("stock", voucher.getStock())
                但是这会导致问题，高并发场景下，只要有一个数据抢到了，可能会导致很多线程因为看到有修改从而放弃尝试
                明明库存还有，却有大量请求被直接拒绝

                于是他给出了第二个处理方式，检查库存是否还有，再决定买不买票，即.gt("stock", 0)
                stock > 0这个WHERE条件，是在数据库服务端执行的，而不是在Java应用里。它实现了“检查并更新”的原子性，
                gt("stock", 0)是一个单条的、原子的数据库操作，本身就是一种基于数据库的、极其健壮的乐观锁实现。

                 */
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                // where id = ? and stock = ?
                .update();
        if(!success) {
            log.error("库存不足！");
            return;
        }
        //创建订单
        save(voucherOrder);
    }
}
