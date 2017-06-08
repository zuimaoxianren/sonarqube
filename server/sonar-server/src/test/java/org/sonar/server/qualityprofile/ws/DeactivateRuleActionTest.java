/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
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
package org.sonar.server.qualityprofile.ws;

import java.net.HttpURLConnection;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.server.ws.WebService;
import org.sonar.db.DbClient;
import org.sonar.db.DbSessionImpl;
import org.sonar.db.DbTester;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.permission.OrganizationPermission;
import org.sonar.db.qualityprofile.ActiveRuleKey;
import org.sonar.db.qualityprofile.QualityProfileDto;
import org.sonar.db.rule.RuleTesting;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.exceptions.UnauthorizedException;
import org.sonar.server.organization.TestDefaultOrganizationProvider;
import org.sonar.server.qualityprofile.RuleActivator;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.TestRequest;
import org.sonar.server.ws.TestResponse;
import org.sonar.server.ws.WsActionTester;

import static org.apache.commons.lang.RandomStringUtils.randomAlphanumeric;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.sonar.server.platform.db.migration.def.VarcharColumnDef.UUID_SIZE;

public class DeactivateRuleActionTest {

  @Rule
  public DbTester dbTester = DbTester.create();
  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private DbClient dbClient = dbTester.getDbClient();
  private RuleActivator ruleActivator = mock(RuleActivator.class);
  private QProfileWsSupport wsSupport = new QProfileWsSupport(dbClient, userSession, TestDefaultOrganizationProvider.from(dbTester));
  private DeactivateRuleAction underTest = new DeactivateRuleAction(dbClient, ruleActivator, userSession, wsSupport);
  private WsActionTester wsActionTester = new WsActionTester(underTest);
  private OrganizationDto defaultOrganization;
  private OrganizationDto organization;

  @Before
  public void before() {
    defaultOrganization = dbTester.getDefaultOrganization();
    organization = dbTester.organizations().insert();
  }

  @Test
  public void define_deactivate_rule_action() {
    WebService.Action definition = wsActionTester.getDef();
    assertThat(definition).isNotNull();
    assertThat(definition.isPost()).isTrue();
    assertThat(definition.params()).extracting(WebService.Param::key).containsExactlyInAnyOrder("profile_key", "rule_key");
  }

  @Test
  public void should_fail_if_not_logged_in() {
    TestRequest request = wsActionTester.newRequest()
      .setMethod("POST")
      .setParam("rule_key", RuleTesting.newRuleDto().getKey().toString())
      .setParam("profile_key", randomAlphanumeric(UUID_SIZE));

    thrown.expect(UnauthorizedException.class);
    request.execute();
  }

  @Test
  public void should_fail_if_not_organization_quality_profile_administrator() {
    userSession.logIn().addPermission(OrganizationPermission.ADMINISTER_QUALITY_PROFILES, defaultOrganization);
    QualityProfileDto qualityProfile = dbTester.qualityProfiles().insert(organization);
    TestRequest request = wsActionTester.newRequest()
      .setMethod("POST")
      .setParam("rule_key", RuleTesting.newRuleDto().getKey().toString())
      .setParam("profile_key", qualityProfile.getKey());

    thrown.expect(ForbiddenException.class);
    request.execute();
  }

  @Test
  public void deactivate_rule_in_default_organization() {
    userSession.logIn().addPermission(OrganizationPermission.ADMINISTER_QUALITY_PROFILES, defaultOrganization);
    QualityProfileDto qualityProfile = dbTester.qualityProfiles().insert(defaultOrganization);
    RuleKey ruleKey = RuleTesting.randomRuleKey();
    TestRequest request = wsActionTester.newRequest()
      .setMethod("POST")
      .setParam("rule_key", ruleKey.toString())
      .setParam("profile_key", qualityProfile.getKey());

    TestResponse response = request.execute();

    assertThat(response.getStatus()).isEqualTo(HttpURLConnection.HTTP_NO_CONTENT);
    ArgumentCaptor<ActiveRuleKey> captor = ArgumentCaptor.forClass(ActiveRuleKey.class);
    Mockito.verify(ruleActivator).deactivateAndUpdateIndex(any(DbSessionImpl.class), captor.capture());
    assertThat(captor.getValue().ruleKey()).isEqualTo(ruleKey);
    assertThat(captor.getValue().qProfile()).isEqualTo(qualityProfile.getKey());
  }

  @Test
  public void deactivate_rule_in_specific_organization() {
    userSession.logIn().addPermission(OrganizationPermission.ADMINISTER_QUALITY_PROFILES, organization);
    QualityProfileDto qualityProfile = dbTester.qualityProfiles().insert(organization);
    RuleKey ruleKey = RuleTesting.randomRuleKey();
    TestRequest request = wsActionTester.newRequest()
      .setMethod("POST")
      .setParam("organization", organization.getKey())
      .setParam("rule_key", ruleKey.toString())
      .setParam("profile_key", qualityProfile.getKey());

    TestResponse response = request.execute();

    assertThat(response.getStatus()).isEqualTo(HttpURLConnection.HTTP_NO_CONTENT);
    ArgumentCaptor<ActiveRuleKey> captor = ArgumentCaptor.forClass(ActiveRuleKey.class);
    Mockito.verify(ruleActivator).deactivateAndUpdateIndex(any(DbSessionImpl.class), captor.capture());
    assertThat(captor.getValue().ruleKey()).isEqualTo(ruleKey);
    assertThat(captor.getValue().qProfile()).isEqualTo(qualityProfile.getKey());
  }
}
