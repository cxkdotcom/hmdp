package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_TYPE_LIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result querylist() {
        //1.查询Redis中是否有首页类型缓存
        String shopTypeList = stringRedisTemplate.opsForValue().get(SHOP_TYPE_LIST_KEY);
        //2.如果缓存存在，直接返回缓存
        if (StrUtil.isNotBlank(shopTypeList)) {
            List<ShopType> typeList = JSONUtil.toList(shopTypeList, ShopType.class);
            return Result.ok(typeList);
        }

        //3.如果缓存不存在，查询数据库
        List<ShopType> typeList = this
                .query().orderByAsc("sort").list();

        //将查询结构存到Redis中
        stringRedisTemplate.opsForValue().set(SHOP_TYPE_LIST_KEY, JSONUtil.toJsonStr(typeList));
        //返回结果
        return Result.ok(typeList);
    }
}
