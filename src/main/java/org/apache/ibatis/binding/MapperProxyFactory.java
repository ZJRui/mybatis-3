/*
 *    Copyright 2009-2021 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.binding;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.binding.MapperProxy.MapperMethodInvoker;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Lasse Voss
 */
public class MapperProxyFactory<T> {

  /**
   * 当前MapperProxyFactory 对象可以创建 实现了MapperInterface接口的代理对象，
   * 比如UserService接口
   */
  private final Class<T> mapperInterface;
  /**
   *
   * 缓存，key是MapperInterface接口 中某方法对应的method对象，value是对应的MapperMethod对象
   *
   */
  private final Map<Method, MapperMethodInvoker> methodCache = new ConcurrentHashMap<>();

  public MapperProxyFactory(Class<T> mapperInterface) {
    this.mapperInterface = mapperInterface;
  }

  public Class<T> getMapperInterface() {
    return mapperInterface;
  }

  public Map<Method, MapperMethodInvoker> getMethodCache() {
    return methodCache;
  }

  @SuppressWarnings("unchecked")
  protected T newInstance(MapperProxy<T> mapperProxy) {
    /**
     * 创建实现了MapperInterface接口的代理对象
     */
    return (T) Proxy.newProxyInstance(mapperInterface.getClassLoader(), new Class[] { mapperInterface }, mapperProxy);
  }

  public T newInstance(SqlSession sqlSession) {

    //创建MapperProxy对象，每次调用都会创安新的MapperProxy对象  这个对象本质上是 InvocationHandler
    // 同时这个 InvocationHandler 对象 持有了methodCache，这个methodCache中保存了目标方法
    //
    //MyBatis的执行原理
    // UserMapper userMapper = sqlSession.getMapper(UserMapper.class);
    //                  User user = new User("tom",new Integer(5));
    //                  userMapper.insertUser(user);
    //
    //首先你要有一个sqlSession对象，SQLSqlSession对象，SqlSession对象中持有executor。
    //然后为Mapper接口创建JDK动态代理对象，指定了InvocationHandler为MapperProxy。创建代理对象的时候将sqlsession交给了InvocationHandler
    //InvocationHandler拦截到方法执行后，根据被调用方法的签名找到对应的MapperMethod，
    //然后执行其execute方法，execute方法中会使用sqlSession执行对应的方法。DefaultSqlSession 中使用到了策略模式，DefaultSqlSession扮演了Context的角色，
    // 而将所有数据库相关的操作全部封装到了Executor接口的实现中。并通过executor字段选择不同的Executor实现
    final MapperProxy<T> mapperProxy = new MapperProxy<>(sqlSession, mapperInterface, methodCache);//注意sqlsession交给了InvocationHandler
    return newInstance(mapperProxy);
  }

}
