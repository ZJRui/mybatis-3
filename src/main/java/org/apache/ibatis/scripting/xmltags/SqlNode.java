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
package org.apache.ibatis.scripting.xmltags;

/**
 * @author Clinton Begin
 */
public interface SqlNode {
  /**
   * apply 方法是sqlnode接口中的唯一方法，该方法会根据用户传入的实参， 参数解析该sqlNode所记录的动态sql节点。
   * 并调用Dynamiccontext。appendSql方法将解析后的sql片段追加到 DynamicContext.sqlBuilder中保存
   *
   * 当SQL 节点下的所有sqlNode完成解析后，我们可以从Dynamiccontext 中获取一条动态生成的完整的sql语句
   *
   * @param context
   * @return
   */
  boolean apply(DynamicContext context);
}
