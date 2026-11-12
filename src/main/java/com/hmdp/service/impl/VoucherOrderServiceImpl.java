package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
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

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //通过代金券ID获取代金券对象（信息）
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断秒杀是否开始
        if(voucher.getBeginTime().isAfter((LocalDateTime.now()))){
            return Result.fail("秒杀尚未开始");
        }
        //判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束");
        }
        //判断库存是否充足
        if(voucher.getStock() < 1){
            return Result.fail("库存不足");
        }

        //获取工具栏类中的simpleRedisLock(加锁类)
        //SimpleRedisLock lock = new SimpleRedisLock("seckill:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("seckill:" + userId);
        //尝试获取锁
        boolean isLocked = lock.tryLock();
        if(!isLocked){
            return Result.fail("不用重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }
        }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 5.1，查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }
        //有库存就在数据库扣一
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        //这个完整sql是update seckill_voucher set stock = stock - 1 where voucher_id = ? and stock > 0


        if(!success){
            return Result.fail("库存不足");
        }
        //前面审查都过了就创建订单（订单有3个必填）
        VoucherOrder voucherOrder = new VoucherOrder();
        //1.订单id（用刚刚写的RedisIdWorker工具类生成）
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //2.用户id（当前登录用户）
        voucherOrder.setUserId(userId);
        //3.代金券id
        voucherOrder.setVoucherId(voucherId);
        //在数据库保存数据
        save(voucherOrder);
        return Result.ok(voucherOrder);
    }
}
