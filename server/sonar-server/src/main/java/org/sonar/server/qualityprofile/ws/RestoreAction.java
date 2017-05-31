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

import java.io.InputStream;
import java.io.InputStreamReader;
import org.apache.commons.io.IOUtils;
import org.sonar.api.resources.Language;
import org.sonar.api.resources.Languages;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.qualityprofile.QProfileDto;
import org.sonar.server.qualityprofile.BulkChangeResult;
import org.sonar.server.qualityprofile.QProfileBackuper;
import org.sonar.server.qualityprofile.QProfileRestoreSummary;
import org.sonar.server.user.UserSession;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.sonar.db.permission.OrganizationPermission.ADMINISTER_QUALITY_PROFILES;
import static org.sonarqube.ws.client.component.ComponentsWsParameters.PARAM_ORGANIZATION;
import static org.sonarqube.ws.client.qualityprofile.QualityProfileWsParameters.ACTION_RESTORE;
import static org.sonarqube.ws.client.qualityprofile.QualityProfileWsParameters.RestoreActionParameters.PARAM_BACKUP;

public class RestoreAction implements QProfileWsAction {

  private final DbClient dbClient;
  private final QProfileBackuper backuper;
  private final Languages languages;
  private final UserSession userSession;
  private final QProfileWsSupport wsSupport;

  public RestoreAction(DbClient dbClient, QProfileBackuper backuper, Languages languages, UserSession userSession, QProfileWsSupport wsSupport) {
    this.dbClient = dbClient;
    this.backuper = backuper;
    this.languages = languages;
    this.userSession = userSession;
    this.wsSupport = wsSupport;
  }

  @Override
  public void define(WebService.NewController controller) {
    WebService.NewAction action = controller.createAction(ACTION_RESTORE)
      .setSince("5.2")
      .setDescription("Restore a quality profile using an XML file. The restored profile name is taken from the backup file, " +
        "so if a profile with the same name and language already exists, it will be overwritten. " +
        "Require Administer Quality Profiles permission.")
      .setPost(true)
      .setHandler(this);

    action.createParam(PARAM_BACKUP)
      .setDescription("A profile backup file in XML format, as generated by api/qualityprofiles/backup " +
        "or the former api/profiles/backup.")
      .setRequired(true);

    QProfileWsSupport.createOrganizationParam(action).setSince("6.4");
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    userSession.checkLoggedIn();

    InputStream backup = request.paramAsInputStream(PARAM_BACKUP);
    String organizationKey = request.param(PARAM_ORGANIZATION);
    InputStreamReader reader = null;

    try (DbSession dbSession = dbClient.openSession(false)) {
      checkArgument(backup != null, "A backup file must be provided");
      reader = new InputStreamReader(backup, UTF_8);

      OrganizationDto organization = wsSupport.getOrganizationByKey(dbSession, organizationKey);
      userSession.checkPermission(ADMINISTER_QUALITY_PROFILES, organization);

      QProfileRestoreSummary summary = backuper.restore(dbSession, reader, organization, null);
      writeResponse(response.newJsonWriter(), organization, summary);
    } finally {
      IOUtils.closeQuietly(reader);
      IOUtils.closeQuietly(backup);
    }
  }

  private void writeResponse(JsonWriter json, OrganizationDto organization, QProfileRestoreSummary summary) {
    QProfileDto profile = summary.getProfile();
    String languageKey = profile.getLanguage();
    Language language = languages.get(languageKey);

    JsonWriter jsonProfile = json.beginObject().name("profile").beginObject();
    jsonProfile
      .prop("organization", organization.getKey())
      .prop("key", profile.getKee())
      .prop("name", profile.getName())
      .prop("language", languageKey)
      .prop("isDefault", false)
      .prop("isInherited", false);
    if (language != null) {
      jsonProfile.prop("languageName", language.getName());
    }
    jsonProfile.endObject();

    BulkChangeResult ruleChanges = summary.getRuleChanges();
    json.prop("ruleSuccesses", ruleChanges.countSucceeded());
    json.prop("ruleFailures", ruleChanges.countFailed());
    json.endObject().close();
  }
}
