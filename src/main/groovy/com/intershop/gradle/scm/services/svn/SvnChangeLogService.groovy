/*
 * Copyright 2015 Intershop Communications AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.intershop.gradle.scm.services.svn

import com.intershop.gradle.scm.extension.VersionExtension
import com.intershop.gradle.scm.services.ScmChangeLogService
import com.intershop.gradle.scm.services.ScmLocalService
import com.intershop.gradle.scm.utils.BranchType
import com.intershop.gradle.scm.utils.ScmType
import com.intershop.gradle.scm.utils.ScmUser
import com.intershop.gradle.scm.version.VersionTag
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.gradle.api.Project
import org.tmatesoft.svn.core.SVNException
import org.tmatesoft.svn.core.SVNLogEntry
import org.tmatesoft.svn.core.SVNLogEntryPath
import org.tmatesoft.svn.core.wc2.ISvnObjectReceiver
import org.tmatesoft.svn.core.wc2.SvnLogMergeInfo
import org.tmatesoft.svn.core.wc2.SvnTarget

import static com.intershop.gradle.scm.services.ChangeLogServiceHelper.*

@CompileStatic
@Slf4j
class SvnChangeLogService extends SvnRemoteService implements ScmChangeLogService {

    private VersionExtension versionExt

    SvnChangeLogService(ScmLocalService sls,
                        VersionExtension versionExt,
                        ScmUser user) {
        super(sls, user)
        this.versionExt = versionExt
        type = sls.type

        // set default value
        filterProject = false
    }

    File changelogFile

    Project project

    String targetVersion

    ScmType type

    boolean filterProject

    void createLog() {
        VersionTag pvt = null
        try {
            pvt = versionExt.getPreviousVersionTag(getTargetVersion())
        } catch(Exception ex) {
            log.warn(ex.getMessage())
        }

        if(pvt) {
            SvnLogMergeInfo mergeInfo = svnOpFactory.createLogMergeInfo()

            mergeInfo.addTarget(SvnTarget.fromURL(getVersionBranch(BranchType.tag).appendPath(pvt.branchObject.name, true)))
            mergeInfo.setSource(SvnTarget.fromURL(localService.url))

            mergeInfo.setDiscoverChangedPaths(true)
            mergeInfo.setRevisionProperties()
            mergeInfo.setFindMerged(false)

            String strURL = localService.projectRootSvnUrl.toString()
            int pos = strURL.lastIndexOf('/')
            String svnProjectName = strURL.substring(pos)

            this.changelogFile.append(getHeader(versionExt.getVersionService().getPreVersion().toString(), pvt.ver.toString()))

            mergeInfo.setReceiver(new ISvnObjectReceiver<SVNLogEntry>() {
                @Override
                void receive(SvnTarget svnTarget, SVNLogEntry logEntry) throws SVNException {
                    getChangelogFile().append(getLineMessage(logEntry.message, Long.toString(logEntry.revision)))
                    logEntry.changedPaths.each { String s, SVNLogEntryPath p ->
                        appendLog(s, p)
                    }
                }

                void appendLog(String s, SVNLogEntryPath p) {
                    if (s.contains(svnProjectName) || ! getFilterProject()) {
                        getChangelogFile().append(
                             getLineChangedFile((getFilterProject() ? s.substring(s.indexOf(svnProjectName)) : s), Character.toString(p.getType())))
                    }
                }
            })
            mergeInfo.run()
        } else {
            this.changelogFile.append(getHeader(versionExt.getVersionService().getPreVersion().toString(), 'not available'))
        }
        this.changelogFile.append(getFooter())
    }

}
