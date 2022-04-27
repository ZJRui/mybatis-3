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

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 * @author Kazuki Shimizu
 */
public class MapperMethod {

  /**
   * MapperMethod 中封装了Mapper接口中对应的方法信息，以及对应sql语句的信息
   *
   * sqlCommand 记录了sql语句的名称和类型
   *
   * SqlCommand 是mapperMethod中定义的内部类，他使用name字段记录了sql语句的名称，使用type字段记录语句类型。
   *
   */
  private final SqlCommand command;
  /**
   * Mapper 接口中对应的方法的相关的信息
   */
  private final MethodSignature method;

  public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
    this.command = new SqlCommand(config, mapperInterface, method);
    this.method = new MethodSignature(config, mapperInterface, method);
  }

  public Object execute(SqlSession sqlSession, Object[] args) {
    Object result;
    /**
     * 下面都使用了sqlsession ，这个sqlSession是哪里来的？
     *
     * MyBatis的执行原理
     *  UserMapper userMapper = sqlSession.getMapper(UserMapper.class);
     *                   User user = new User("tom",new Integer(5));
     *                   userMapper.insertUser(user);
     *
     * 首先你要有一个sqlSession对象，SQLSqlSession对象，SqlSession对象中持有executor。
     * 然后为Mapper接口创建JDK动态代理对象，指定了InvocationHandler为MapperProxy。创建代理对象的时候将sqlsession交给了InvocationHandler
     * InvocationHandler拦截到方法执行后，根据被调用方法的签名找到对应的MapperMethod，
     * 然后执行其execute方法，execute方法中会使用sqlSession执行对应的方法。DefaultSqlSession 中使用到了策略模式，DefaultSqlSession扮演了Context的角色，而将所有数据库相关的操作全部封装到了Executor接口的实现中。并通过executor字段选择不同的Executor实现
     *
     */
    switch (command.getType()) {
      case INSERT: {
        /**
         * 使用paraNameResolver 处理 args[] 数组（用户传入的实际参数列表）将用户传入的实参与指定的参数名关联起来
         *
         */
        Object param = method.convertArgsToSqlCommandParam(args);
        /**
         *调用sqlSession.insert ,rowCountResult 方法会根据method字段中记录的方法的返回值类型
         * 对结果进行转换。
         *
         */
        result = rowCountResult(sqlSession.insert(command.getName(), param));
        break;
      }
      case UPDATE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.update(command.getName(), param));
        break;
      }
      case DELETE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.delete(command.getName(), param));
        break;
      }
      case SELECT:
        if (method.returnsVoid() && method.hasResultHandler()) {
          /**
           * 如果返回值为void 且ResultSet通过ResultHandler处理
           */
          executeWithResultHandler(sqlSession, args);
          result = null;
        } else if (method.returnsMany()) {
          result = executeForMany(sqlSession, args);
        } else if (method.returnsMap()) {
          result = executeForMap(sqlSession, args);
        } else if (method.returnsCursor()) {
          result = executeForCursor(sqlSession, args);
        } else {
          //处理返回值为单一对象的方法
          Object param = method.convertArgsToSqlCommandParam(args);
          result = sqlSession.selectOne(command.getName(), param);
          if (method.returnsOptional()
              && (result == null || !method.getReturnType().equals(result.getClass()))) {
            result = Optional.ofNullable(result);
          }
        }
        break;
      case FLUSH:
        result = sqlSession.flushStatements();
        break;
      default:
        throw new BindingException("Unknown execution method for: " + command.getName());
    }
    if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
      throw new BindingException("Mapper method '" + command.getName()
          + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
    }
    return result;
  }

  private Object rowCountResult(int rowCount) {
    final Object result;
    if (method.returnsVoid()) {
      result = null;
    } else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
      result = rowCount;
    } else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
      result = (long) rowCount;
    } else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
      result = rowCount > 0;
    } else {
      throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: " + method.getReturnType());
    }
    return result;
  }

  private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
    /**
     * 当mapper接口中定义的方法准备使用Resulthandler 处理查询结果集， 会通过 executeWithResulthandler处理
     *
     *
     * 获取sql对应的MappedStatement对象，MappedStatement中记录了sql语句相关信息
     */
    MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
    if (!StatementType.CALLABLE.equals(ms.getStatementType())
        && void.class.equals(ms.getResultMaps().get(0).getType())) {
      throw new BindingException("method " + command.getName()
          + " needs either a @ResultMap annotation, a @ResultType annotation,"
          + " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
    }
    /**
     * 将实参 转为 <形参名,实参值>的map
     */
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      /**
       * 检测参数列表中是否有RouBounds类型的参数
       *
       * 获取RowBounds对象，根据MethodSignature.rowBoundsIndex字段指定位置，从args数组中查找。
       *
       */
      RowBounds rowBounds = method.extractRowBounds(args);
      /**
       * 执行查询，并由指定的ResultHandler处理查询对象
       */
      sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
    } else {
      sqlSession.select(command.getName(), param, method.extractResultHandler(args));
    }
  }

  private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
    List<E> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectList(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.selectList(command.getName(), param);
    }
    // issue #510 Collections & arrays support
    if (!method.getReturnType().isAssignableFrom(result.getClass())) {
      if (method.getReturnType().isArray()) {
        return convertToArray(result);
      } else {
        return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
      }
    }
    return result;
  }

  private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
    Cursor<T> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectCursor(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.selectCursor(command.getName(), param);
    }
    return result;
  }

  private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
    Object collection = config.getObjectFactory().create(method.getReturnType());
    MetaObject metaObject = config.newMetaObject(collection);
    metaObject.addAll(list);
    return collection;
  }

  @SuppressWarnings("unchecked")
  private <E> Object convertToArray(List<E> list) {
    Class<?> arrayComponentType = method.getReturnType().getComponentType();
    Object array = Array.newInstance(arrayComponentType, list.size());
    if (arrayComponentType.isPrimitive()) {
      for (int i = 0; i < list.size(); i++) {
        Array.set(array, i, list.get(i));
      }
      return array;
    } else {
      return list.toArray((E[]) array);
    }
  }

  private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
    Map<K, V> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey(), rowBounds);
    } else {
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey());
    }
    return result;
  }

  public static class ParamMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = -2212268410512043556L;

    @Override
    public V get(Object key) {
      if (!super.containsKey(key)) {
        throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
      }
      return super.get(key);
    }

  }

  public static class SqlCommand {

    private final String name;
    private final SqlCommandType type;

    public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
      /**
       * sql语句的名称 由mapper接口的名称与对应的方法名称组成
       */
      final String methodName = method.getName();
      final Class<?> declaringClass = method.getDeclaringClass();
      MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass,
          configuration);
      if (ms == null) {
        /**
         * 处理@Flush注解
         */
        if (method.getAnnotation(Flush.class) != null) {
          name = null;
          type = SqlCommandType.FLUSH;
        } else {
          throw new BindingException("Invalid bound statement (not found): "
              + mapperInterface.getName() + "." + methodName);
        }
      } else {
        name = ms.getId();
        type = ms.getSqlCommandType();
        if (type == SqlCommandType.UNKNOWN) {
          throw new BindingException("Unknown execution method for: " + name);
        }
      }
    }

    public String getName() {
      return name;
    }

    public SqlCommandType getType() {
      return type;
    }

    private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName,
        Class<?> declaringClass, Configuration configuration) {
      //sql语句的名称 由mapper接口的名称与对应的方法名称组成
      String statementId = mapperInterface.getName() + "." + methodName;
      /**
       * 检测是否有该名称的sql语句
       */
      if (configuration.hasStatement(statementId)) {
        /**
         * 从configuration的mapperStatements集合中查找对应的MapperStatement对象
         * MapperStatement对象中封装了SQL语句相关的信息，在mybatis初始化时创建
         */
        return configuration.getMappedStatement(statementId);
      } else if (mapperInterface.equals(declaringClass)) {
        return null;
      }
      /**
       * 如果指定的方法是在父接口中定义的。 则在此处进行集成结构处理
       */
      for (Class<?> superInterface : mapperInterface.getInterfaces()) {
        if (declaringClass.isAssignableFrom(superInterface)) {
          MappedStatement ms = resolveMappedStatement(superInterface, methodName,
              declaringClass, configuration);
          if (ms != null) {
            return ms;
          }
        }
      }
      return null;
    }
  }

  public static class MethodSignature {

    /**
     * 返回值类型是否为Collection类型或者数组类型
     */
    private final boolean returnsMany;
    /**
     * 返回值类型是否为map类型
     */
    private final boolean returnsMap;
    /**
     * 返回值类型是否为void
     */
    private final boolean returnsVoid;
    /**
     * 返回值类型是否为cursor类型
     */
    private final boolean returnsCursor;
    /**
     *
     */
    private final boolean returnsOptional;
    /**
     * 返回值类型
     */
    private final Class<?> returnType;
    /**
     * 如果返回值类型为amp，则该字段记录了作为key的列名
     */
    private final String mapKey;
    /**
     * 用来标记该方法参数列表中 ResultHandler类型参数的位置
     */
    private final Integer resultHandlerIndex;
    /**
     * 用来标记该方法参数列表中RouBounds类型参数的位置
     */
    private final Integer rowBoundsIndex;
    /**
     * 使用ParamNameResolver处理Mapper接口中定义的方法的参数列表。
     * ParamNameResolver使用name字段（sortedMap<Integer,String>) 记录了参数在参数列表中的位置索引 和参数名称之间的关系
     * key表示参数在参数列表中的索引位置，value表示参数名称 参数名称可以通过@Param注解指定，如果没有指定@Param注解，则使用参数索引作为其名称。
     * 如果参数列表中包含RowBounds类型或ResultSet类型的参数，则这两种类型的参数不会被记录到name集合中，这就会导致参数索引与名称不一致。例如
     * methodA(int a, RowBounds rb , int b) 方法对应你的names集合中为{{0,"0"},{2,"1"}}
     */
    private final ParamNameResolver paramNameResolver;

    public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
      /**
       * 解析方法参数值类型
       */
      Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
      if (resolvedReturnType instanceof Class<?>) {
        this.returnType = (Class<?>) resolvedReturnType;
      } else if (resolvedReturnType instanceof ParameterizedType) {
        this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
      } else {
        this.returnType = method.getReturnType();
      }
      this.returnsVoid = void.class.equals(this.returnType);
      this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();
      this.returnsCursor = Cursor.class.equals(this.returnType);
      this.returnsOptional = Optional.class.equals(this.returnType);
      /**
       * 如果MethodSignature对应方法的返回值类型是Map且指定了@Mapkey注解，则使用getMapKey方法处理
       */
      this.mapKey = getMapKey(method);
      this.returnsMap = this.mapKey != null;
      this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
      this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);
      this.paramNameResolver = new ParamNameResolver(configuration, method);
    }

    /**
     * 负责将 args[]数组 （用户传入的实参列表）转换成sql语句对应的参数列表，通过paramNameResolver.getNameParams实现
     *
     * @param args
     * @return
     */
    public Object convertArgsToSqlCommandParam(Object[] args) {
      return paramNameResolver.getNamedParams(args);
    }

    public boolean hasRowBounds() {
      return rowBoundsIndex != null;
    }

    public RowBounds extractRowBounds(Object[] args) {
      return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
    }

    public boolean hasResultHandler() {
      return resultHandlerIndex != null;
    }

    public ResultHandler extractResultHandler(Object[] args) {
      return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
    }

    public Class<?> getReturnType() {
      return returnType;
    }

    public boolean returnsMany() {
      return returnsMany;
    }

    public boolean returnsMap() {
      return returnsMap;
    }

    public boolean returnsVoid() {
      return returnsVoid;
    }

    public boolean returnsCursor() {
      return returnsCursor;
    }

    /**
     * return whether return type is {@code java.util.Optional}.
     *
     * @return return {@code true}, if return type is {@code java.util.Optional}
     * @since 3.5.0
     */
    public boolean returnsOptional() {
      return returnsOptional;
    }

    private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
      /**
       * 查找指定类型的参数在参数列表中的位置
       * 在被调用的地方传递过来的paramType是 RowBounds和ResultHandler
       */
      Integer index = null;
      final Class<?>[] argTypes = method.getParameterTypes();
      /**
       * 遍历MethodSignature对应方法的参数列表
       */
      for (int i = 0; i < argTypes.length; i++) {
        if (paramType.isAssignableFrom(argTypes[i])) {
          if (index == null) {//记录paramType类型参数在参数列表中的位置索引
            index = i;
          } else {//RowBounds和ResultHandler类型的参数只能有一个，不能重复出现
            throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
          }
        }
      }
      return index;
    }

    public String getMapKey() {
      return mapKey;
    }

    private String getMapKey(Method method) {
      String mapKey = null;
      if (Map.class.isAssignableFrom(method.getReturnType())) {
        final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
        if (mapKeyAnnotation != null) {
          mapKey = mapKeyAnnotation.value();
        }
      }
      return mapKey;
    }
  }

}
