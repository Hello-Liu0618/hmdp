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
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone) ) {
            //如果不符合则返回错误信息
            return Result.fail("手机号格式错误!");
        }
//        //如果符合则保存验证码到session
//        String code = RandomUtil.randomNumbers(6);
//        session.setAttribute("code", code);
        //生成并保存验证码到redis中
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, 1, TimeUnit.MINUTES);//保存验证码到redis中有效期为1分钟

        //发送验证码
        //需要企业资质调用阿里短信服务，故此处跳过
        log.info("发送验证码成功: {}", code);

        return Result.ok();
    }

    /**
     * 用户登录，相关信息通过session存储传递
     * @param loginFormDTO
     * @param session
     * @return
     */
//    public Result login(LoginFormDTO loginForm, HttpSession session) {
//        //提取手机号与验证码
//        String phone = loginForm.getPhone();
//        String code =  loginForm.getCode();
//
//        //校验手机号是否合法
//        if ( RegexUtils.isPhoneInvalid(phone) ) {
//            //手机号非法
//            return Result.fail("手机号格式错误!");
//        }
//        //手机号格式正确
//
//        //校验验证码是否正确
//        String cacheCode = (String) session.getAttribute("code");
//        if ( code == null || !code.equals(cacheCode)) {
//            //验证码错误
//            return Result.fail("验证码错误!");
//        }
//        //验证码正确
//
//        User user = query().eq("phone", phone).one();
//
//        if ( user == null ) {
//            //创建用户
//            user = new User();
//            user.setPhone(phone);
//            //保存用户信息到数据库中
//            userMapper.insert(user);
//        }
//        //保存用户信息到session中
//        session.setAttribute("user", user);
//        return Result.ok();
//    }

    /**
     * 用户登录，相关信息通过redis存储传递
     * @param loginFormDTO
     * @param session
     * @return
     */
    public Result login(LoginFormDTO loginFormDTO, HttpSession session) {
        String phone = loginFormDTO.getPhone();
        if ( RegexUtils.isPhoneInvalid(phone) ) {
            //手机号非法
            return Result.fail("手机号格式错误!");
        }
        String code = loginFormDTO.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if ( null == cacheCode ) {
            //验证码失效
            return Result.fail("验证码失效, 请重新获取!");
        } if ( ! code.equals(cacheCode) ) {
            //验证码错误
            return Result.fail("验证码错误!");
        }
        //验证码一致
        //根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //若为新用户则为其注册
        if ( user == null ) {
            user = new User();
            user.setPhone(phone);
            userMapper.insert(user);
        }
        //将用户数据存入redsi中并设置token用于访问
        String token = UUID.randomUUID().toString();
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user,userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);//设置token的有效期为20分钟，即20分钟无操作则登录失效

        return Result.ok(token);
    }
}
