package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
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

    // 首页展示的热门博客
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户并判断是否被点过赞
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    // 点击查询首页的博客
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在！");
        }
        queryBlogUser(blog);
        //查询是否被点过赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 2. 如果用户未登录（也就是游客模式），直接返回，不需要查询点赞状态
        if (user == null) {
            return; // 用户没登录，isLike 默认为 null 或 false，无需处理
        }
        // 3. 用户已登录，才获取 ID 进行判断
        Long userId = user.getId();
        //判断是否点过赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    //博客点赞逻辑
    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        //判断是否点过赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //如果未点赞，可以点赞
        if(score == null){
            boolean isSuccess =
                    update().setSql("liked = liked + 1").eq("id",id).update();
            if(isSuccess){
                //相当于按照发布时间作为排序的分数
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else{
            boolean isSuccess =
                    update().setSql("liked = liked - 1").eq("id",id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    //查找博客的前几个点赞用户
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key,0,4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 解析出其中的用户id
        /*
        Stream 流是 Java 8+ 引入的函数式编程工具，本质是「集合 / 数组的 “流水线处理通道”」
        核心特性可以用 “惰性、链式、不可变、并行友好” 四个词概括
        惰性求值，即中间操作（如 map）只 “记录操作逻辑”，不实际执行；只有调用终止操作（如 collect），才会触发整个流的计算。

        Long.valueOf() 是 java.lang.Long 类的静态方法
        核心作用是将其他类型（如 String、int、double 等）转换为 Long 类型对象
        这里 Long::valueOf 是方法引用，等价于 Lambda 表达式 obj -> Long.valueOf(obj.toString())
         */
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 这里跟python有点类似
        String idStr = StrUtil.join(",",ids);
        List<UserDTO> userDTOS = userService.query()
                // 等价于where id in (5,4,3,2,1) order by field(id,5,4,3,2,1)
                .in("id",ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    // 保存发布的博客
    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());

        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败！");
        }
        // 查询该笔记发布者的所有粉丝
        List<Follow> follows = followService.lambdaQuery().eq(Follow::getFollowUserId, user.getId()).list();
        // 推送
        for(Follow follow : follows){
            // 获取粉丝的id
            Long userId = follow.getUserId();
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        // 得到一个元组，元组内存档就是收件箱内按照取规则取出来的信息（id和时间戳）
        // typedTuples 存的就是一个浏览窗口的博客信息
        // max：本次取的最大时间戳，offset：与最小时间戳相同的博客条数
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }

        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int tempOffset = 1;
        for(ZSetOperations.TypedTuple<String> tuple : typedTuples){
            // 把博客的id放进去
            ids.add(Long.valueOf(tuple.getValue()));
            // 可能的最小时间戳，就是下一次的最大起始值
            long tempMinTime = tuple.getScore().longValue();
            // 先假设这个就是最小的时间戳，offset++
            if(tempMinTime == minTime){
                tempOffset++;
            }else{
                // 如果这个还不是最小，就更新最小值，让offset变回1
                minTime = tempMinTime;
                tempOffset = 1;
            }
        }

        String idStr = StrUtil.join(",",ids);
        List<Blog> blogs = lambdaQuery()
                .in(Blog::getId,ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        for(Blog blog : blogs){
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        }

        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(tempOffset);
        scrollResult.setMinTime(minTime);

        return Result.ok(scrollResult);
    }

    // 博客详情页上显示的用户，单独拉出来成一个方法
    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
