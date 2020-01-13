/**
 * Copyright 2017-2020 O2 Czech Republic, a.s.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cz.o2.proxima.direct.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import cz.o2.proxima.direct.randomaccess.RandomOffset;
import cz.o2.proxima.repository.AttributeDescriptor;
import cz.o2.proxima.repository.EntityDescriptor;
import cz.o2.proxima.storage.StreamElement;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HsqldbSqlStatementFactory implements SqlStatementFactory {
  private String tableName = "DUMMYTABLE";
  private String primaryKey = "id";

  public void setup(EntityDescriptor entity, URI uri, HikariDataSource dataSource)
      throws SQLException {
    // currently no-op
  }

  public PreparedStatement get(
      HikariDataSource dataSource, AttributeDescriptor<?> desc, Object value) throws SQLException {
    PreparedStatement statement =
        dataSource
            .getConnection()
            .prepareStatement(
                String.format(
                    "SELECT %s,%s FROM %s WHERE id  = ? LIMIT 1",
                    desc.getName(), primaryKey, tableName));
    statement.setString(1, value.toString()); // @TODO
    return statement;
  }

  public PreparedStatement list(HikariDataSource dataSource, RandomOffset offset, int limit)
      throws SQLException {
    return dataSource
        .getConnection()
        .prepareStatement(
            String.format(
                "SELECT %s FROM %s ORDER BY %s LIMIT %d",
                primaryKey, tableName, primaryKey, limit));
  }

  @Override
  public PreparedStatement update(HikariDataSource dataSource, StreamElement element)
      throws SQLException {
    Connection connection = dataSource.getConnection();
    PreparedStatement statement;
    if (element.isDelete() || element.isDeleteWildcard()) {
      statement =
          connection.prepareStatement(
              String.format("DELETE FROM %s WHERE %s = ?", tableName, primaryKey));
      statement.setString(1, element.getKey());
      return statement;
    } else if (element.getValue() != null) {
      statement =
          connection.prepareStatement(
              String.format(
                  "MERGE INTO %s AS T USING (VALUES(?,?)) as vals(%s, %s) ON T.%s = vals.%s "
                      + "WHEN MATCHED THEN UPDATE SET T.%s = vals.%s "
                      + "WHEN NOT MATCHED THEN INSERT VALUES vals.%s, vals.%s",
                  tableName,
                  primaryKey,
                  element.getAttribute(),
                  primaryKey,
                  primaryKey,
                  element.getAttribute(),
                  element.getAttribute(),
                  primaryKey,
                  element.getAttribute()));

      statement.setString(1, element.getKey());
      statement.setString(2, new String(element.getValue()));
      return statement;
    }
    return null;
  }
}
