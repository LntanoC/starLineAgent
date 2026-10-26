/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.starlineagent.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.starlineagent.framework.exception.ClientException;
import com.nageoffer.ai.starlineagent.user.controller.request.LoginRequest;
import com.nageoffer.ai.starlineagent.user.controller.request.RegisterRequest;
import com.nageoffer.ai.starlineagent.user.controller.vo.LoginVO;
import com.nageoffer.ai.starlineagent.user.dao.entity.UserDO;
import com.nageoffer.ai.starlineagent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.starlineagent.user.enums.UserRole;
import com.nageoffer.ai.starlineagent.user.service.AuthService;
import com.nageoffer.ai.starlineagent.user.service.UserPasswordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_AVATAR_URL = "https://avatars.githubusercontent.com/u/583231?v=4";
    private static final String DEFAULT_ADMIN_USERNAME = "admin";

    private final UserMapper userMapper;
    private final UserPasswordService passwordService;

    @Override
    public LoginVO login(LoginRequest requestParam) {
        if (requestParam == null) {
            throw new ClientException("用户名或密码不能为空");
        }
        String username = StrUtil.trimToNull(requestParam.getUsername());
        String password = requestParam.getPassword();
        if (username == null || StrUtil.isBlank(password)) {
            throw new ClientException("用户名或密码不能为空");
        }
        UserDO user = findByUsername(username);
        if (user == null || !passwordService.matches(password, user.getPassword())) {
            throw new ClientException("用户名或密码错误");
        }
        if (user.getId() == null) {
            throw new ClientException("用户信息异常");
        }

        upgradeLegacyPassword(user, password);
        return createLogin(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO register(RegisterRequest requestParam) {
        if (requestParam == null) {
            throw new ClientException("注册信息不能为空");
        }
        String username = StrUtil.trimToNull(requestParam.getUsername());
        String password = requestParam.getPassword();
        if (username == null || StrUtil.isBlank(password)) {
            throw new ClientException("用户名和密码不能为空");
        }
        int usernameLength = username.codePointCount(0, username.length());
        if (usernameLength < 3 || usernameLength > 64) {
            throw new ClientException("用户名长度需为 3 到 64 个字符");
        }
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < 8 || passwordBytes > 72) {
            throw new ClientException("密码需至少 8 个字符，且不能超过 72 字节");
        }
        if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
            throw new ClientException("该用户名不可注册");
        }
        if (findByUsername(username) != null) {
            throw new ClientException("用户名已被占用");
        }

        UserDO user = UserDO.builder()
                .username(username)
                .password(passwordService.encode(password))
                .role(UserRole.USER.getCode())
                .avatar(null)
                .build();
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 数据库唯一约束兜住并发注册及软删除用户名冲突。
            throw new ClientException("用户名已被占用");
        }
        return createLogin(user);
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    private UserDO findByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            return null;
        }
        return userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
        );
    }

    private void upgradeLegacyPassword(UserDO user, String rawPassword) {
        if (!passwordService.needsUpgrade(user.getPassword())) {
            return;
        }
        try {
            user.setPassword(passwordService.encode(rawPassword));
            userMapper.updateById(user);
        } catch (RuntimeException e) {
            // 哈希迁移失败不阻断已验证的旧账户登录；下次登录仍会尝试迁移。
            log.warn("用户密码哈希升级失败 userId={}", user.getId(), e);
        }
    }

    private LoginVO createLogin(UserDO user) {
        String loginId = user.getId().toString();
        StpUtil.login(loginId);
        String avatar = StrUtil.isBlank(user.getAvatar()) ? DEFAULT_AVATAR_URL : user.getAvatar();
        return new LoginVO(loginId, user.getRole(), StpUtil.getTokenValue(), avatar);
    }
}
