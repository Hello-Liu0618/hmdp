package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //从request中获取session
        HttpSession session = request.getSession();
        //获取user
        Object user = session.getAttribute("user");

        if ( user == null ) {
            //设置状态为402
            response.setStatus(401);
            return false;
        }
        //创建UserDTO对象
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user,userDTO);

        //将用户DTO信息保存到LocalThread中
        UserHolder.saveUser(userDTO);
        return true;
    }
}
