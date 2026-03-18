package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone) ) {
            //如果不符合则返回错误信息
            return Result.fail("手机号格式错误!");
        }
        //如果符合则保存验证码到session
        String code = RandomUtil.randomNumbers(6);
        session.setAttribute("code", code);

        //发送验证码
        //需要企业资质调用阿里短信服务，故此处跳过
        log.info("发送验证码成功: {}", code);

        return Result.ok();
    }

    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //提取手机号与验证码
        String phone = loginForm.getPhone();
        String code =  loginForm.getCode();

        //校验手机号是否合法
        if ( RegexUtils.isPhoneInvalid(phone) ) {
            //手机号非法
            return Result.fail("手机号格式错误!");
        }
        //手机号格式正确

        //校验验证码是否正确
        String cacheCode = (String) session.getAttribute("code");
        if ( code == null || !code.equals(cacheCode)) {
            //验证码错误
            return Result.fail("验证码错误!");
        }
        //验证码正确

        User user = query().eq("phone", phone).one();

        if ( user == null ) {
            //创建用户
            user = new User();
            user.setPhone(phone);
            //保存用户信息到数据库中
            userMapper.insert(user);
        }
        //保存用户信息到session中
        session.setAttribute("user", user);
        return Result.ok();
    }
}
