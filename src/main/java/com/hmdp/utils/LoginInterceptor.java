package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class LoginInterceptor implements HandlerInterceptor {

    //这个类没有加注解，是我们自己new出来的，spring没有帮我们管理，因此需要我们自己手动注入这个类
    //用构造函数，谁用谁自己注入
    private StringRedisTemplate stringRedisTemplate;
    public LoginInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //当前线程的每次请求会被拦截器拦截下来，判断之后放到ThreadLocal里面让controller获取

        /*
        获取session
        HttpSession session = request.getSession();
        获取用户
        Object user = session.getAttribute("user");
         */

        //获取请求头中的token并通过其找到redis里面的用户
        String token = request.getHeader("authorization");
        //没有就拦截
        if(StrUtil.isBlank(token)){
            response.setStatus(401);
            return false;
        }
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        //判断是否存在
        if(userMap.isEmpty()){
            response.setStatus(401);
            return false;
        }

        //将用户信息存入当前请求线程的ThreadLocal中
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);

        //刷新token的有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //请求处理完毕后，拦截器清理ThreadLocal中的用户信息，防止内存泄漏和后续请求被错误地设置
        UserHolder.removeUser();
    }
}
