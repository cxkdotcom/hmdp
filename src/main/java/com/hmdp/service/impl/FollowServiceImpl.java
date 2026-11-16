package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
//取关和关注功能
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UserServiceImpl userServiceImpl;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        if(isFollow){
            //关注（数据库添加二人关系）
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            //如果修改成功加入Redis的set集合（因为set集合可以求交集，可以用于查询共同关注）
            if(isSuccess){
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else {
            //取关(删除sql是delete from follow where user_id = ? and follow_user_id = ?)
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId)
            );
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    //判断是否关注
    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        //只需要查表是否有
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        //判断
        return Result.ok(count > 0);
    }

    //查看共同关注
    @Override
    public Result followCommon(Long id) {
        //先获取当前用户的id（因为现在只传过来目标用户的id）
        Long userId = UserHolder.getUser().getId();
        //当前用户key
        String key1 = "follow:" + userId;
        //目标用户key
        String key2 = "follow:" + id;
        //用两个key求交集(得到共同关注的id)
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //根据共同关注id查询用户
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //要转化为UserDTO
        List<UserDTO> users = userServiceImpl.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
