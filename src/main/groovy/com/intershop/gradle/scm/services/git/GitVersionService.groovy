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

import com.intershop.gradle.scm.services.ScmLocalService
import com.intershop.gradle.scm.services.ScmVersionService
import com.intershop.gradle.scm.utils.BranchObject
import com.intershop.gradle.scm.utils.BranchType
import com.intershop.gradle.scm.utils.PrefixConfig
import com.intershop.gradle.scm.utils.ScmException
import com.intershop.gradle.scm.utils.ScmKey
import com.intershop.gradle.scm.utils.ScmUser
import com.intershop.gradle.scm.version.ReleaseFilter
import com.intershop.gradle.scm.version.ScmBranchFilter
import com.intershop.gradle.scm.version.ScmVersionObject
import com.intershop.gradle.scm.version.VersionTag
import com.intershop.release.version.Version
import groovy.util.logging.Slf4j
import org.eclipse.jgit.api.*
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevSort
import org.eclipse.jgit.revwalk.RevWalk
/**
 * This is the container for the remote access to the used SCM of a project.
 * It calculates the version and has methods to create a branch, a tag or
 * move the working copy to a special version.
 * This is the implementation for the remote access to the Git remote project.
 */
@Slf4j
class GitVersionService extends GitRemoteService implements ScmVersionService{

    /**
     * Constructs a valid remote client for SCM access.
     *
     * @param sls information from the working copy
     * @param versionBranchType branch type which is used for version calculation
     * @param versionType version type - three or four version digits
     * @param patternDigits Number of digits used for the search pattern of valid branches
     * with version information.
     */
    GitVersionService(ScmLocalService sls,
                      ScmUser user = null,
                      ScmKey key = null) {
        super(sls, user, key)
        localService = sls
    }

    /**
     * Returns an object from the SCM with additional information.
     *
     * @return version object from scm
     */
    public ScmVersionObject getVersionObject() {
        ScmVersionObject rv = null

        // identify headId of the working copy
        ObjectId headId = ((GitLocalService)localService).repository.resolve(this.localService.getRevID())

        if(headId) {
            Map<String, String> tags = getTagMap()
            Map<String, String> branches = getBranchMap()

            int pos = 0

            RevCommit commit
            String tagName
            String branchName

            ScmBranchFilter tagFilter = getBranchFilter()
            ScmBranchFilter branchFilter = getBranchFilter(localService.featureBranchName ? localService.getBranchType() : BranchType.branch)

            String version = null

            // create walk and find next ... to identify changes
            RevWalk walk = new RevWalk(((GitLocalService)localService).repository)
            walk.sort(RevSort.TOPO)
            RevCommit head = walk.parseCommit(headId)

            // version from tag, if tag is available
            if (!tags.isEmpty()) {
                walk.markStart(head)
                for (commit = walk.next(); commit != null; commit = walk.next()) {
                    tagName = tags[commit.id.name()]
                    if (tagName) {
                        // commit is a tag
                        version = tagFilter.getVersionStr(tagName)
                        if (version) {
                            log.debug('Version from tag {}', tagName)
                            // commit is a tag with version information
                            rv = new ScmVersionObject(tagName, Version.forString(version, versionExt.getVersionType()), false)
                            break
                        } else {
                            log.debug('Version {} does not match to tag filter')
                        }
                    } else {
                        ++pos
                        log.debug('Next step in walk to tag')
                    }
                }
            }

            // version from branch, if branch is available
            if (!(tagName || branches.isEmpty())) {
                walk.reset()
                walk.markStart(head)

                pos = 0
                for (commit = walk.next(); commit != null; commit = walk.next()) {
                    branchName = branches[commit.id.name()]
                    if (branchName) {
                        version = branchFilter.getVersionStr(branchName)
                        if (version) {
                            log.debug('Version from branch {}', branchName)
                            rv = new ScmVersionObject(branchName, Version.forString(version, versionExt.getVersionType()), true)
                            rv.fromBranchName = (branchName == this.localService.branchName)
                            break
                        } else {
                            log.debug('Version does not match to branch filter')
                        }
                    } else {
                        ++pos
                        log.debug('Next step in walk to tag')
                    }
                }
            }

            walk.dispose()
            // tag is available, but there are commits between current rev and tag
            if (rv && (pos > 0 || this.localService.changed)) {
                rv.setChanged(true)
            }
        }

        // fallback ...
        if(! rv) {
            // check branch name
            rv = getFallbackVersion()
        }

        return rv
    }

    public Map<Version, VersionTag> getVersionTagMap() {
        Map<String, BranchObject> branchMap = this.getTagMap(new ReleaseFilter(localService.prefixes, getPreVersion()))

        Map<Version, VersionTag> versionTags = [:]
        branchMap.each {key, bo ->
            Version v = Version.valueOf(bo.version)
            versionTags.put(v, new VersionTag(v, bo))
        }

        return versionTags
    }

    /**
     * Moves the working copy to a specified version
     *
     * @param version
     * @param type Branchtype of the target branch
     * @return the revision id of the working after the move
     */
    public String moveTo(String version, BranchType type = BranchType.branch) {
        //checkout branch, wc is detached
        log.debug('git checkout {}', version)

        String branchName = ''
        Map<String, String> versionMap = null
        String path = ''

        if(checkBranch(BranchType.tag ,version)) {
            branchName = getBranchName(BranchType.tag, version)
            versionMap = getTagMap()
            path = 'tags/'
        } else if(checkBranch(type, version)) {
            branchName = getBranchName(type, version)
            versionMap = getBranchMap()
            path = 'origin/'
        } else {
            throw new ScmException("Version '${version}' does not exists")
        }

        String objectID = ''

        versionMap.entrySet().each { def entry ->
            if(entry.value == branchName) {
                objectID = entry.getKey()
            }
        }

        if(objectID == '') {
            throw new ScmException("Version '${version}' does not exists.")
        } else {
            log.info('Branch {} with id {} will be checked out.', branchName, objectID)
        }

        CheckoutCommand cmd = ((GitLocalService)localService).client.checkout()
        cmd.setName("${path}${branchName}")
        Ref ref = cmd.call()

        log.debug('Reference is {}', ref)

        return objectID
    }

    /**
     * Creates a tag with the specified version.
     *
     * @param version
     * @return the revision id of the tag
     */
    public String createTag(String version, String revid = this.localService.getRevID()) {
        if(remoteConfigAvailable) {
            // check if tag exits
            if (checkBranch(BranchType.tag, version)) {
                throw new ScmException("Tag for ${version} exists on this repo.")
            }

            String tagName = getBranchName(BranchType.tag, version)

            // create tag
            TagCommand cmd = ((GitLocalService)localService).client.tag()
            cmd.name = tagName
            cmd.setObjectId(getObjectId(revid))
            cmd.message = "Tag ${tagName} created by gradle plugin"
            cmd.annotated = true
            cmd.forceUpdate = false

            Ref ref = cmd.call()

            // push changes to remote
            pushCmd()
            log.info("Tag ${tagName} was create on ${this.localService.branchName}")
            return ref
        } else {
            log.info('No remote connection available, because there is no credentials configuration.')
            return ''
        }
    }

    /**
     * Creates a branch with the specified version.
     *
     * @param version
     * @param featureBranch true, if this is a version of a feature branch
     * @return the revision id of the branch
     */
    public String createBranch(String version, boolean featureBranch, String revid = this.localService.getRevID()) {
        if(remoteConfigAvailable) {
            // check if branch exits
            if (checkBranch(featureBranch ? localService.getBranchType() : BranchType.branch, version) ) {
                throw new ScmException("Branch for ${version} exists in this repo.")
            }

            String branchName = getBranchName(featureBranch ? localService.getBranchType() : BranchType.branch, version)

            // create branch
            CreateBranchCommand cmd = ((GitLocalService)localService).client.branchCreate()
            cmd.setName(branchName)
            cmd.startPoint = revid
            cmd.force = true
            Ref ref = cmd.call()

            // push changes to remote
            pushCmd()
            log.info("Branch ${branchName} was created")
            return ref
        } else {
            log.info('No remote connection available, because there is no credentials configuration.')
            return ''
        }
    }

    /**
     * Returns true, if the specified release version is available.
     *
     * @param version
     * @return true, if the specified release version is available
     */
    public boolean isReleaseVersionAvailable(String version) {
        return checkBranch(BranchType.tag, version)
    }

    /**
     * Check if branch of special version with special branch type exists.
     *
     * @param type
     * @param version
     * @return true if the branch exists
     */
    private boolean checkBranch(BranchType type, String version) {
        String name = getBranchName(type, version)
        String path = ''

        if(type == BranchType.tag) {
            if(remoteConfigAvailable) {
                fetchTagsCmd()
            }
            path = 'refs/tags/'
        } else {
            if(remoteConfigAvailable) {
                fetchAllCmd()
            }
            path = 'refs/heads/'
        }

        // list all tags and branches
        LsRemoteCommand cmd = Git.lsRemoteRepository()
        addCredentialsToCmd(cmd)
        cmd.setRemote(this.localService.getRemoteUrl())
        cmd.setHeads(true)
        cmd.setTags(true)

        // check if tag or branch is available
        Collection<Ref> refs = cmd.call()
        List rv = refs.collect { Ref r ->
            if(r.getName() == "${path}${name}") {
                return r.getName()
            }
        }
        rv.removeAll([null])
        return rv.size() > 0
    }

    /**
     * Map with rev ids and assigned tag names.
     */
    private Map<String, String> getTagMap() {
        //specify return value
        Map<String, Ref> rv = [:]

        // fetch all tags from repo
        if(remoteConfigAvailable) {
            fetchTagsCmd()
        }
        //specify walk
        final RevWalk walk = new RevWalk(((GitLocalService)localService).repository)

        //specifx filter
        ScmBranchFilter filter = getBranchFilter()

        //check tags and calculate
        ((GitLocalService)localService).repository.getTags().each { tagName, rev ->
            if(filter.getVersionStr(tagName)) {
                RevCommit rc = walk.parseCommit(rev.objectId)
                rv.put(ObjectId.toString(rc), tagName.toString())
            }
        }
        walk.dispose()
        return rv
    }

    /**
     * Map with rev ids and assigned branche names.
     */
    private Map<String, String> getBranchMap() {
        //specify return value
        Map<String, Ref> rv = [:]

        if(remoteConfigAvailable) {
            fetchAllCmd()
        }

        //specify walk
        final RevWalk walk = new RevWalk(((GitLocalService)localService).repository)

        ListBranchCommand cmd = ((GitLocalService)localService).client.branchList()
        cmd.setListMode(ListBranchCommand.ListMode.ALL)
        List<Ref> refList = cmd.call()

        refList.each { Ref ref ->
            RevCommit rc = walk.parseCommit(ref.objectId)
            String name = ref.getName().toString()
            String branchName = name.substring(name.lastIndexOf('/') + 1)
            if(branchName != 'master') {
                rv.put(ObjectId.toString(rc), name.substring(name.lastIndexOf('/') + 1))
            }
        }
        walk.dispose()

        return rv
    }

    /**
     * fetch all changes from remote
     * remote connection is necessary
     */
    private void fetchAllCmd() {
        try {
            // fetch all
            FetchCommand cmd = ((GitLocalService)localService).client.fetch()
            cmd.remote = 'origin'
            cmd.setCheckFetchedObjects(true)
            addCredentialsToCmd(cmd)
            cmd.call()
        } catch(InvalidRemoteException nrex) {
            log.warn('No remote repository is available!')
        } catch(TransportException tex) {
            log.warn('It was not possible to fetch all. Please check your credential configuration.', tex)
        }
    }

    /**
     * push changes (tag/branch) to remote
     * remote connection is necessary
     */
    private void pushCmd() {
        // push changes
        try {
            PushCommand cmd = ((GitLocalService)localService).client.push()
            addCredentialsToCmd(cmd)
            cmd.setPushAll()
            cmd.setPushTags()
            cmd.remote =  'origin'
            cmd.force = true
            cmd.call()
        } catch(GitAPIException gitEx) {
            log.error(gitEx.message, gitEx.cause)
        }
    }
}
