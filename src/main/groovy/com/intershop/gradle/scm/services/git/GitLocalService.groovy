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


package com.intershop.gradle.scm.services.git

import com.intershop.gradle.scm.extension.ScmExtension
import com.intershop.gradle.scm.services.ScmLocalService
import com.intershop.gradle.scm.utils.BranchType
import groovy.transform.CompileDynamic
import groovy.util.logging.Slf4j
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.LogCommand
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk

@Slf4j
@CompileDynamic
class GitLocalService extends ScmLocalService{

    /*
     * Git repo object for all operations
     */
    private final Repository gitRepo
    /*
     * Git client for all operations
     */
    private final Git gitClient

    /*
     * Remote URL of the project
     */
    private final String remoteUrl

    /**
     * This constructs a SCM info service. It contains all necessary information from
     * the working copy without remote access to the SCM. It must be implemented for
     * supported SCMs.
     *
     * @param projectDir
     * @param prefixes
     */
    @CompileDynamic
    GitLocalService(File projectDir, ScmExtension scmExtension) {
        super(projectDir, scmExtension)

        gitRepo = new RepositoryBuilder()
                .readEnvironment()
                .findGitDir(projectDir)
                .build()
        gitClient = new Git(gitRepo)

        branchName = gitRepo.getBranch()

        if(branchName == 'master') {
            branchType = BranchType.trunk
        } else {
            def mfb = branchName =~ /${prefixes.getFeatureBranchPattern()}/
            if(mfb.matches() && mfb.count == 1 && (mfb[0].size() == 5 || mfb[0].size() == 6)) {
                branchType = BranchType.featureBranch
                featureBranchName = mfb[0][mfb[0].size() - 1]
            } else {
                String tn = checkHeadForTag()
                if(tn) {
                    branchType = BranchType.tag
                    branchName = tn
                } else {
                    branchType = BranchType.branch
                }
            }
        }

        log.info('Branch name is {}', branchName)

        Config config = gitRepo.getConfig()
        remoteUrl = config.getString('remote', 'origin', 'url')

        log.info('Remote URL is {}', remoteUrl)

        try {
            LogCommand logLocal = gitClient.log()
            List<String> revsLocal = []
            Iterable<RevCommit> logsLocal = logLocal.call()
            logsLocal.each { RevCommit rev ->
                revsLocal.add(rev.toString())
            }
        }catch (Exception ex) {
            log.warn('No repo info available!')
        }

        Status status = gitClient.status().call()

        changed = status.untracked.size() > 0 || status.uncommittedChanges.size() > 0 || status.removed.size() > 0 || status.added.size() > 0 || status.changed.size() > 0 || status.modified.size() > 0
    }

    private String checkHeadForTag() {
        String rvTagName = ''
        RevWalk rw = new RevWalk(repository)
        gitRepo.getTags().each {tagName, rev ->
            if(ObjectId.toString(rw.parseCommit(rev.objectId).id) == getRevID()) {
                rvTagName = tagName
            }
        }
        rw.dispose()
        return rvTagName
    }

    /**
     * Access for the GitRepo Object
     * @return
     */
    public Repository getRepository() {
        return gitRepo
    }

    /**
     * Access for the GitClient Object
     * @return
     */
    public Git getClient() {
        return gitClient
    }

    /**
     * It returns the remote url, calculated from the properties of the working copy (read only).
     *
     * @return remote url
     */
    @Override
    public String getRemoteUrl() {
        return remoteUrl
    }

    /**
     * The revision id from the working copy (read only).
     *
     * @return revision id
     */
    @Override
    public String getRevID() {
        ObjectId id = gitRepo.resolve(Constants.HEAD)
        String rv = ''

        if(id) {
            rv = id.getName()
        }

        return rv
    }
}
