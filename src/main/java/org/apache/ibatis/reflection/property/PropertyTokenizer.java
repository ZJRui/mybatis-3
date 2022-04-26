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
package org.apache.ibatis.reflection.property;

import java.util.Iterator;

/**
 * @author Clinton Begin
 */
public class PropertyTokenizer implements Iterator<PropertyTokenizer> {
  /**
   * 表达式解析。 比如User 中有一个orders List属性 order中会有一个List items属性， Item中会有name属性。
   *
   * 现在有一个sql查询出来的列名 item1表示  第一个订单的第一个条目的name， item2表示第一个订单的第二个条目的name，那么
   * 使用resultSet如何配置映射？
   *
   * <resultMap id="xx"  type="User">
   *
   *      <id column="id" property="id"></id>
   *      <result property="orders[0].items[0].name"  column="item1"></result>
   *      <result property="orders[0].items[1].name" column="item2"></result>
   *
   *  </resultMap>
   *  orders[0].items[0].name 这种由"." 和"[]"组成的表达式是由PropertyTokenizer进行解析的。
   *
   *
   *
   * PropertyTokenizer继承自Iterator接口，可以迭代处理嵌套多层表达式。
   *
   */
  //当前表达式的名称
  private String name;
  //当前表达式的索引名
  private final String indexedName;
  //索引下标
  private String index;
  //子表达式
  private final String children;

  public PropertyTokenizer(String fullname) {
    /**
     * 查找 . 的位置
     */
    int delim = fullname.indexOf('.');
    if (delim > -1) {
      //初始化name和children
      name = fullname.substring(0, delim);

      children = fullname.substring(delim + 1);
    } else {
      name = fullname;
      children = null;
    }
    //初始化indexName
    indexedName = name;

    delim = name.indexOf('[');
    if (delim > -1) {
      //初始化index
      index = name.substring(delim + 1, name.length() - 1);
      name = name.substring(0, delim);
    }
  }

  public String getName() {
    return name;
  }

  public String getIndex() {
    return index;
  }

  public String getIndexedName() {
    return indexedName;
  }

  public String getChildren() {
    return children;
  }

  @Override
  public boolean hasNext() {
    return children != null;
  }

  @Override
  public PropertyTokenizer next() {
    return new PropertyTokenizer(children);
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException("Remove is not supported, as it has no meaning in the context of properties.");
  }
}
