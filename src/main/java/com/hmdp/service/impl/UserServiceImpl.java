package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    //以手机号和验证码为键值对放到redis
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("wrong phone number");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        /*
        保存验证码到session
        session.setAttribute("code",code);
        */
        //保存验证码到Redis
        stringRedisTemplate.opsForValue()
                .set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //发送验证码
        log.debug("已发送短信验证码：{}",code);
        return Result.ok();
    }

    @Override
    //登录成功后要把这个被认可的用户放进redis，自然需要一张小身份卡片来找到redis里对应的信息
    //这个身份卡片是每次请求的时候都要携带的，但是我们不能信任客户端
    //因此就需要用随机生成的token来作为key，这样客户端拿到了身份卡也没啥用，还是要到redis里面获取
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("wrong phone number");
        }
        /*
        if(session.getAttribute("code") == null ||
                !session.getAttribute("code").equals(loginForm.getCode())){
            return Result.fail("验证码错误");
        }
        */
        //从redis中获取验证码做校验
        if(stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone) == null ||
                !stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone)
                        .equals(loginForm.getCode())){
            return Result.fail("验证码错误");
        }
        //根据手机号查询用户（mybatisPlus）
        User user = query().eq("phone", phone).one();
        if(user == null){
            user = createUserByPhone(phone);
        }
        //随机生成token作为令牌
        String token = UUID.randomUUID().toString();
        //将user对象转换为hashmap储存
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        /*
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
         */
        //保存到redis
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);

        //把token给客户端
        return Result.ok(token);
    }

    private User createUserByPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(8));
        save(user);

        return user;
    }
}
