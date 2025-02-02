/*
 * SonarQube
 * Copyright (C) 2009-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.platform.db.migration.version.v96;

import java.sql.SQLException;
import java.sql.Types;
import org.junit.Rule;
import org.junit.Test;
import org.sonar.db.CoreDbTester;

import static org.sonar.db.CoreDbTester.createForSchema;
import static org.sonar.server.platform.db.migration.version.v96.AddWebhookSecretToAlmSettingsTable.ALM_SETTINGS_TABLE_NAME;
import static org.sonar.server.platform.db.migration.version.v96.AddWebhookSecretToAlmSettingsTable.WEBHOOK_SECRET_COLUMN_NAME;
import static org.sonar.server.platform.db.migration.version.v96.DbConstants.WEBHOOK_SECRET_COLUMN_SIZE;

public class AddWebhookSecretToAlmSettingsTableTest {

  @Rule
  public final CoreDbTester db = createForSchema(AddWebhookSecretToAlmSettingsTableTest.class, "schema.sql");

  private final AddWebhookSecretToAlmSettingsTable underTest = new AddWebhookSecretToAlmSettingsTable(db.database());

  @Test
  public void column_language_should_be_added() throws SQLException {
    db.assertColumnDoesNotExist(ALM_SETTINGS_TABLE_NAME, WEBHOOK_SECRET_COLUMN_NAME);

    underTest.execute();

    db.assertColumnDefinition(ALM_SETTINGS_TABLE_NAME, WEBHOOK_SECRET_COLUMN_NAME, Types.VARCHAR, WEBHOOK_SECRET_COLUMN_SIZE, true);
  }

  @Test
  public void migration_should_be_reentrant() throws SQLException {
    db.assertColumnDoesNotExist(ALM_SETTINGS_TABLE_NAME, WEBHOOK_SECRET_COLUMN_NAME);

    underTest.execute();
    underTest.execute();

    db.assertColumnDefinition(ALM_SETTINGS_TABLE_NAME, WEBHOOK_SECRET_COLUMN_NAME, Types.VARCHAR, WEBHOOK_SECRET_COLUMN_SIZE, true);
  }
}