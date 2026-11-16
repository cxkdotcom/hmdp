package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
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
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建堵塞队列
    private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024 * 1024);
    //创建线程池来处理堵塞队列中的订单
    private static final ExecutorService KILL_ORDER_HEANDER = Executors.newSingleThreadExecutor();

    //初始化方法（让系统一运行就马上处理堵塞队列中的订单）
    @PostConstruct
    public void init() {
        KILL_ORDER_HEANDER.submit(new killOrderHeader());
    }

    public class killOrderHeader implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder voucherOrder = orderTask.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("取订单异常", e);
                }
            }
        }

        //处理订单的方法（这是子线程无法获取代理对象）
        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            Long voucherId = voucherOrder.getVoucherId();
            //尝试获取锁（按用户+券维度加锁，防止重复下单）
            RLock lock = redissonClient.getLock("lock:order:" + voucherId + ":" + userId);
            boolean isLocked = lock.tryLock();
            if (!isLocked) {
                log.error("不能重复下单");
                return;
            }
            try {
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                lock.unlock();
            }
        }
    }


    //这个是主线程
    //获取代理对象proxy
    private IVoucherOrderService proxy;

        @Override
        public Result seckillVoucher(Long voucherId) {
            //获取当前用户id
            Long userId = UserHolder.getUser().getId();
            //执行lua脚本
            Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(), userId.toString());
            //判断结果是否为0
            if (result != 0) {
                //为1，库存不足，为2，不能重复下单
                return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
            }
            //为0证明有秒杀资格（可以放入堵塞队列中）
            VoucherOrder voucherOrder = new VoucherOrder();
            //1.订单id（用刚刚写的RedisIdWorker工具类生成）
            Long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //2.用户id（当前登录用户）
            voucherOrder.setUserId(userId);
            //3.代金券id
            voucherOrder.setVoucherId(voucherId);
            //将订单信息放入堵塞队列
            orderTask.add(voucherOrder);
            //获取代理对象proxy
            proxy = (IVoucherOrderService) AopContext.currentProxy();
            //返回订单id
            return Result.ok(orderId);

        }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        //通过代金券ID获取代金券对象（信息）
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        if(voucher.getBeginTime().isAfter((LocalDateTime.now()))){
//            return Result.fail("秒杀尚未开始");
//        }
//        //判断秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//        }
//        //判断库存是否充足
//        if(voucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//
//        //获取工具栏类中的simpleRedisLock(加锁类)
//        //SimpleRedisLock lock = new SimpleRedisLock("seckill:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("seckill:" + userId);
//        //尝试获取锁
//        boolean isLocked = lock.tryLock();
//        if(!isLocked){
//            return Result.fail("不用重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//        }

        @Override
        @Transactional
        public void createVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            // 5.1，查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                log.error("用户已经购买过了");
                return;
            }
            //有库存就在数据库扣一
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();
            //这个完整sql是update seckill_voucher set stock = stock - 1 where voucher_id = ? and stock > 0


            if (!success) {
                log.error("库存不足");
                return;
            }

            //创建订单
            save(voucherOrder);

        }
}
