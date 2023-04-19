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
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.Objects;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author shadow_maples
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2. 手机号格式不正确，返回错误
            return Result.fail("手机号格式错误！");
        }
        //3. 生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4. 将验证码存入session
        session.setAttribute("code", code);
        //5. 发送验证码
        log.debug("发送短信验证码成功，验证码为:{}", code);
        return Result.ok("验证码发送成功，请查收");
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        String phone = loginForm.getPhone();
        //1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3. 校验验证码
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (RegexUtils.isCodeInvalid(code)) {
            return Result.fail("验证码格式错误！");
        }
        if (cacheCode.toString().equals(code)) {
            //4. 验证码跟session不一致，报错
            return Result.fail("验证码错误");
        }
        //5. 验证码一致，根据手机号去数据库查询用户
        User user = query().eq("phone", phone).one();
        //6. 判断用户是否存在，不存在的话注册一个新用户
        if (Objects.isNull(user)) {
            user = createUserWithPhone(phone);
        }
        session.setAttribute("user", user);
        return null;
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        return user;
    }
}
