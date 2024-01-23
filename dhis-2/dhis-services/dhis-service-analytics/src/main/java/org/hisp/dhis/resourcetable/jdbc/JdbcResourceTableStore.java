/*
 * Copyright (c) 2004-2022, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hisp.dhis.resourcetable.jdbc;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hisp.dhis.analytics.AnalyticsTableHook;
import org.hisp.dhis.analytics.AnalyticsTableHookService;
import org.hisp.dhis.analytics.AnalyticsTablePhase;
import org.hisp.dhis.resourcetable.ResourceTable;
import org.hisp.dhis.resourcetable.ResourceTableStore;
import org.hisp.dhis.system.util.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * @author Lars Helge Overland
 */
@Slf4j
@RequiredArgsConstructor
@Service("org.hisp.dhis.resourcetable.ResourceTableStore")
public class JdbcResourceTableStore implements ResourceTableStore {
  // -------------------------------------------------------------------------
  // Dependencies
  // -------------------------------------------------------------------------

  private final AnalyticsTableHookService analyticsTableHookService;

  private final JdbcTemplate jdbcTemplate;

  // -------------------------------------------------------------------------
  // ResourceTableStore implementation
  // -------------------------------------------------------------------------

  @Override
  public void generateResourceTable(ResourceTable<?> resourceTable) {
    log.info("Generating resource table: '{}'", resourceTable.getTableName());

    final Clock clock = new Clock().startClock();
    final String createTableSql = resourceTable.getCreateTempTableStatement();
    final Optional<String> populateTableSql = resourceTable.getPopulateTempTableStatement();
    final Optional<List<Object[]>> populateTableContent =
        resourceTable.getPopulateTempTableContent();
    final List<String> createIndexSql = resourceTable.getCreateIndexStatements();
    final String analyzeTableSql = String.format("analyze %s;", resourceTable.getTempTableName());

    // ---------------------------------------------------------------------
    // Drop temporary table if it exists
    // ---------------------------------------------------------------------

    jdbcTemplate.execute(resourceTable.getDropTempTableIfExistsStatement());

    // ---------------------------------------------------------------------
    // Create temporary table
    // ---------------------------------------------------------------------

    log.debug("Create table SQL: '{}'", createTableSql);

    jdbcTemplate.execute(createTableSql);

    // ---------------------------------------------------------------------
    // Populate temporary table through SQL or object batch update
    // ---------------------------------------------------------------------

    if (populateTableSql.isPresent()) {
      log.debug("Populate table SQL: '{}'", populateTableSql.get());

      jdbcTemplate.execute(populateTableSql.get());
    } else if (populateTableContent.isPresent()) {
      List<Object[]> content = populateTableContent.get();

      log.debug("Populate table content rows: {}", content.size());

      if (content.size() > 0) {
        int columns = content.get(0).length;

        batchUpdate(columns, resourceTable.getTempTableName(), content);
      }
    }

    // ---------------------------------------------------------------------
    // Invoke hooks
    // ---------------------------------------------------------------------

    List<AnalyticsTableHook> hooks =
        analyticsTableHookService.getByPhaseAndResourceTableType(
            AnalyticsTablePhase.RESOURCE_TABLE_POPULATED, resourceTable.getTableType());

    if (!hooks.isEmpty()) {
      analyticsTableHookService.executeAnalyticsTableSqlHooks(hooks);

      log.info("Invoked resource table hooks: '{}'", hooks.size());
    }

    // ---------------------------------------------------------------------
    // Create indexes
    // ---------------------------------------------------------------------

    for (final String sql : createIndexSql) {
      log.debug("Create index SQL: '{}'", sql);

      jdbcTemplate.execute(sql);
    }

    // ---------------------------------------------------------------------
    // Analyze
    // ---------------------------------------------------------------------

    jdbcTemplate.execute(analyzeTableSql);

    log.debug("Analyzed resource table: '{}'", resourceTable.getTempTableName());

    // ---------------------------------------------------------------------
    // Swap tables
    // ---------------------------------------------------------------------

    jdbcTemplate.execute(resourceTable.getDropTableIfExistsStatement());

    jdbcTemplate.execute(resourceTable.getRenameTempTableStatement());

    log.debug("Swapped resource table: '{}'", resourceTable.getTableName());

    log.info("Resource table '{}' update done: '{}'", resourceTable.getTableName(), clock.time());
  }

  @Override
  public void batchUpdate(int columns, String tableName, List<Object[]> batchArgs) {
    if (columns == 0 || tableName == null) {
      return;
    }

    StringBuilder builder = new StringBuilder("insert into " + tableName + " values (");

    for (int i = 0; i < columns; i++) {
      builder.append("?,");
    }

    builder.deleteCharAt(builder.length() - 1).append(")");

    jdbcTemplate.batchUpdate(builder.toString(), batchArgs);
  }
}
