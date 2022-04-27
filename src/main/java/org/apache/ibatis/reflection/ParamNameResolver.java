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
package org.apache.ibatis.reflection;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

public class ParamNameResolver {

  public static final String GENERIC_NAME_PREFIX = "param";

  private final boolean useActualParamName;

  /**
   * <p>
   * The key is the index and the value is the name of the parameter.<br />
   * The name is obtained from {@link Param} if specified. When {@link Param} is not specified,
   * the parameter index is used. Note that this index could be different from the actual index
   * when the method has special parameters (i.e. {@link RowBounds} or {@link ResultHandler}).
   * </p>
   * <ul>
   * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
   * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
   * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
   * </ul>
   *
   *
   * MethodSignature中使用ParamNameResolver处理Mapper接口中定义的方法的参数列表。
   * ParamNameResolver使用name字段（sortedMap<Integer,String>) 记录了参数在参数列表中的位置索引 和参数名称之间的关系
   * key表示参数在参数列表中的索引位置，value表示参数名称 参数名称可以通过@Param注解指定，如果没有指定@Param注解，则使用参数索引作为其名称。
   * 如果参数列表中包含RowBounds类型或ResultSet类型的参数，则这两种类型的参数不会被记录到name集合中，这就会导致参数索引与名称不一致。例如
   * methodA(int a, RowBounds rb , int b) 方法对应你的names集合中为{{0,"0"},{2,"1"}}
   *
   */
  private final SortedMap<Integer, String> names;

  /**
   * 记录对应方法的参数列表中是否使用了@param注解，
   *
   */
  private boolean hasParamAnnotation;

  public ParamNameResolver(Configuration config, Method method) {
    /**
     * MethodSignature中使用ParamNameResolver处理Mapper接口中定义的方法的参数列表。
     * ParamNameResolver使用name字段（sortedMap<Integer,String>) 记录了参数在参数列表中的位置索引 和参数名称之间的关系
     * key表示参数在参数列表中的索引位置，value表示参数名称 参数名称可以通过@Param注解指定，如果没有指定@Param注解，则使用参数索引作为其名称。
     * 如果参数列表中包含RowBounds类型或ResultSet类型的参数，则这两种类型的参数不会被记录到name集合中，这就会导致参数索引与名称不一致。例如
     * methodA(int a, RowBounds rb , int b) 方法对应你的names集合中为{{0,"0"},{2,"1"}}
     */
    this.useActualParamName = config.isUseActualParamName();
    /**
     * 获取参数列表中每个参数的类型
     */
    final Class<?>[] paramTypes = method.getParameterTypes();
    /**
     * 获取参数列表上的注解
     */
    final Annotation[][] paramAnnotations = method.getParameterAnnotations();
    final SortedMap<Integer, String> map = new TreeMap<>();
    int paramCount = paramAnnotations.length;
    // get names from @Param annotations
    //遍历所有方法参数
    for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
      if (isSpecialParameter(paramTypes[paramIndex])) {
        // skip special parameters  如果是RowBounds类型或者ResultHandler类型则跳过对该参数的分析
        continue;
      }
      String name = null;
      /**
       * 遍历参数上的注解集合
       */
      for (Annotation annotation : paramAnnotations[paramIndex]) {
        /**
         * @param注解出现过一次，就将hasParamAnnotation初始化为true
         *
         */
        if (annotation instanceof Param) {
          hasParamAnnotation = true;
          name = ((Param) annotation).value();//获取注解指定的参数名称
          break;
        }
      }
      /**
       * 这个if代码段 解释了 上面 实例中的 names集合项的value为什么是 0 和1
       */
      if (name == null) {
        //name为空，则表示该参数没有对应的@Param注解，则根据配置决定是否使用参数实际名称作为其名称
        // @Param was not specified.
        if (useActualParamName) {
          name = getActualParamName(method, paramIndex);
        }
        if (name == null) { //使用参数的索引作为其名称
          // use the parameter index as the name ("0", "1", ...)
          // gcode issue #71
          name = String.valueOf(map.size());
        }
      }
      map.put(paramIndex, name);
    }
    names = Collections.unmodifiableSortedMap(map);
  }

  private String getActualParamName(Method method, int paramIndex) {
    return ParamNameUtil.getParamNames(method).get(paramIndex);
  }

  private static boolean isSpecialParameter(Class<?> clazz) {
    return RowBounds.class.isAssignableFrom(clazz) || ResultHandler.class.isAssignableFrom(clazz);
  }

  /**
   * Returns parameter names referenced by SQL providers.
   *
   * @return the names
   */
  public String[] getNames() {
    return names.values().toArray(new String[0]);
  }

  /**
   * <p>
   * A single non-special parameter is returned without a name.
   * Multiple parameters are named using the naming rule.
   * In addition to the default names, this method also adds the generic names (param1, param2,
   * ...).
   * </p>
   *
   * @param args
   *          the args
   * @return the named params
   */
  public Object getNamedParams(Object[] args) {
    /**
     * 接收参数是用户传入的实参列表，  将实参与对应的形参名称进行关联
     *
     *
     */
    final int paramCount = names.size();
    if (args == null || paramCount == 0) {
      return null;
    } else if (!hasParamAnnotation && paramCount == 1) {//未使用@Param且只有一个参数
      Object value = args[names.firstKey()];
      return wrapToMapIfCollection(value, useActualParamName ? names.get(0) : null);
    } else {
      /**
       *处理 使用了@param 注解指定参数名称  或有多个参数的情况
       */
      final Map<String, Object> param = new ParamMap<>();
      int i = 0;
      /**
       * 遍历形参列表，将 <形参，实参>键值对放入param中
       */
      for (Map.Entry<Integer, String> entry : names.entrySet()) {
        param.put(entry.getValue(), args[entry.getKey()]);
        // add generic param names (param1, param2, ...)
        final String genericParamName = GENERIC_NAME_PREFIX + (i + 1);
        /**
         * 下面视为了参数创建 param+索引 格式的默认参数名称， 例如 param1 ， param2等并添加到param集合中。
         */
        // ensure not to overwrite parameter named with @Param
        if (!names.containsValue(genericParamName)) {
          param.put(genericParamName, args[entry.getKey()]);
        }
        i++;
      }
      return param;
    }
  }

  /**
   * Wrap to a {@link ParamMap} if object is {@link Collection} or array.
   *
   * @param object a parameter object
   * @param actualParamName an actual parameter name
   *                        (If specify a name, set an object to {@link ParamMap} with specified name)
   * @return a {@link ParamMap}
   * @since 3.5.5
   */
  public static Object wrapToMapIfCollection(Object object, String actualParamName) {
    if (object instanceof Collection) {
      ParamMap<Object> map = new ParamMap<>();
      map.put("collection", object);
      if (object instanceof List) {
        map.put("list", object);
      }
      Optional.ofNullable(actualParamName).ifPresent(name -> map.put(name, object));
      return map;
    } else if (object != null && object.getClass().isArray()) {
      ParamMap<Object> map = new ParamMap<>();
      map.put("array", object);
      Optional.ofNullable(actualParamName).ifPresent(name -> map.put(name, object));
      return map;
    }
    return object;
  }

}
