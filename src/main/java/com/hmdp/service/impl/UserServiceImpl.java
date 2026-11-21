package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1。校验手机号（错误就报错）
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式无效");
        }
        //2.校验验证码（错误就报错）
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String nowCode = loginForm.getCode();
        if(nowCode == null||!nowCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //3.查看用户是否存在
        User user = query().eq("phone", phone).one();
        //4.不存在就创建
        if (user == null) {
            user = createUser(phone);
        }
        //5.生成token（会话ID），用于登录校验，将用户信息保存到redis中
        //5.1生成token
        String token = UUID.randomUUID().toString();
        //5.2将用户信息转化为hash形式，并储存到redis中
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        //5.3为token设置有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //5.存入session（无论是新创建还是本来就有）(现在已经弃用该方法，变为token登录)
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok(token);
    }

    //完成当天签到功能
    @Override
    public Result sign() {
        //1.获取当前签到的用户的id
        Long id = UserHolder.getUser().getId();
        //2.获取当前的时间
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String month = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + id + month;
        //4.获取现在是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //4.存入Redis（用mapbit存储）
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    //补签功能
    @Override
    public Result sign2(int monthday) {
        //1.获取当前签到的用户的id
        Long id = UserHolder.getUser().getId();
        //2.获取当前的时间
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String month = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + id + month;
        //4.补签
        stringRedisTemplate.opsForValue().setBit(key,monthday-1,true);
        return Result.ok();
    }



    //计算连续签到天数
    @Override
    public Result signCount() {
        //1.获取当前签到的用户的id
        Long id = UserHolder.getUser().getId();
        //2.获取当前的时间
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String month = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + id + month;
        //4.获取现在是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取Redis中的签到记录(返回一个十进制)
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        int count = 0;
        while (true){
            if ((num & 1) == 0) {
                break;
            } else {
                count++;
            }
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUser(String phone) {
            User user = new User();
            user.setPhone(phone);
            user.setNickName(USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
            save(user);
            return user;
    }
}
