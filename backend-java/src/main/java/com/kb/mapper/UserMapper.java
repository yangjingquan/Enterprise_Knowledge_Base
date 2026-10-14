package com.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.kb.entity.User;

/**
 * User 表的数据访问接口。
 * 继承 BaseMapper 后即拥有 insert / selectById / selectList / updateById / deleteById 等常用方法，无需手写 SQL。
 */
public interface UserMapper extends BaseMapper<User> {
}
