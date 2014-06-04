/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.rule;

import org.sonar.api.ServerComponent;
import org.sonar.api.rule.RuleKey;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.server.rule.index.RuleIndex;
import org.sonar.server.rule.index.RuleNormalizer;
import org.sonar.server.rule.index.RuleQuery;
import org.sonar.server.rule.index.RuleResult;
import org.sonar.server.search.QueryOptions;
import org.sonar.server.user.UserSession;

import javax.annotation.CheckForNull;

import java.util.Set;

/**
 * @since 4.4
 */
public class RuleService implements ServerComponent {

  private final RuleIndex index;
  private final RuleUpdater ruleUpdater;

  public RuleService(RuleIndex index, RuleUpdater ruleUpdater) {
    this.index = index;
    this.ruleUpdater = ruleUpdater;
  }

  @CheckForNull
  public org.sonar.server.rule.Rule getByKey(RuleKey key) {
    return index.getByKey(key);
  }

  public RuleQuery newRuleQuery() {
    return new RuleQuery();
  }

  public RuleResult search(RuleQuery query, QueryOptions options) {
    return index.search(query, options);
  }

  /**
   * List all tags, including system tags, defined on rules
   */
  public Set<String> listTags() {
    /** using combined _TAGS field of ES until ES update that has multiTerms aggregation */
    return index.terms(RuleNormalizer.RuleField._TAGS.field());
  }

  public void update(RuleUpdate update) {
    UserSession userSession = UserSession.get();
    userSession.checkLoggedIn();
    userSession.checkGlobalPermission(GlobalPermissions.QUALITY_PROFILE_ADMIN);

    ruleUpdater.update(update, UserSession.get());
  }
}
