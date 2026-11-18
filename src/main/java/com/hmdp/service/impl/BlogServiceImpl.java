package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;


    @Override
    public Result queryBlogById(Long id) {
        //根据blogid查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("博客不存在");
        }
        //返回博客应该带有用户信息（所以这一步应该查询用户信息）
        blogUser(blog);
        islike(blog);
        return Result.ok(blog);
    }




    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page =query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            blogUser(blog);
            islike(blog);
        });
        return Result.ok(records);
    }

    //点赞功能
    @Override
    public Result likeBlog(Long id) {
        //1.获取用户id
        Long userId = UserHolder.getUser().getId();
        //2.用Redisd判断用户是否点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //3.1如果未点赞，则数据库点赞数+1
        if(score == null) {
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2在Redis中的set集合记录用户点赞
            if(isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        } else {
            //4.1如果已点赞，则数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2在Redis中的set集合删除用户点赞
            if(isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    //点赞排行榜
    @Override
    public Result queryLikesBlog(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //运用Redis的SortedSet查找点赞时间前五的分数
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 2. 解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3. 根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        //为了用户信息安全性，要返回安全的的UserDTO形式
        List<UserDTO> usersDTO = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(usersDTO);
    }

    @Override
    public Result saveBlog(Blog blog) {
        //1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("上传博客失败");
        }
        //3.现在需要将新键的博客id发到粉丝的收件箱（收件箱用Redis的sortedSet）
        //3.1获取当前博主的粉丝（就是数据库中follow表中follow_user_id为当前博主id的用户）
        List<Follow> follows = followService.query().eq("follow_user_id", blog.getUserId()).list();
        //3.2将当前博客的id发给每个粉丝（每个粉丝的id作为key，博客id作为值）
        Long blogId = blog.getId();
        follows.forEach(follow -> {
            //获取每个分数的id作为key
            Long userId = follow.getUserId();
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key,blogId.toString(),System.currentTimeMillis());
        });
        // 返回id
        return Result.ok(blog.getId());
    }

    //按时间戳查询
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.通过userId查询粉丝ZSet获取博客id
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        //3.解析出其中的用户id
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //4.1获取每个用户的id
            ids.add(Long.valueOf(tuple.getValue()));
            //4.2获取分数
            long time = tuple.getScore().longValue();
            if(time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        //4.根据用户id查询用户
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            // 5.1.查询blog有关的用户
            blogUser(blog);
            // 5.2.查询blog是否被点赞
            //点赞功能
            islike(blog);
        }

        //5.封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }


    //以下为工具方法
    //补全博客信息
    private void blogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    private void islike(Blog blog) {
        //1.获取用户id
        UserDTO user = UserHolder.getUser();
        if(user == null){
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = UserHolder.getUser().getId();
        //2.用Redisd判断用户是否点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
}
