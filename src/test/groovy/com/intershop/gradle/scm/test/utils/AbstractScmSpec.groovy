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
package com.intershop.gradle.scm.test.utils

import com.intershop.gradle.test.AbstractIntegrationSpec
import groovy.util.logging.Slf4j
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.FetchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.tmatesoft.svn.core.SVNCommitInfo
import org.tmatesoft.svn.core.SVNNodeKind
import org.tmatesoft.svn.core.SVNURL
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager
import org.tmatesoft.svn.core.io.SVNRepository
import org.tmatesoft.svn.core.io.SVNRepositoryFactory
import org.tmatesoft.svn.core.wc.SVNWCUtil
import org.tmatesoft.svn.core.wc2.SvnCheckout
import org.tmatesoft.svn.core.wc2.SvnOperationFactory
import org.tmatesoft.svn.core.wc2.SvnRemoteDelete
import org.tmatesoft.svn.core.wc2.SvnTarget

@Slf4j
class AbstractScmSpec extends AbstractIntegrationSpec {

    protected void svnCheckOut(File target, String source) {
        final SvnOperationFactory svnOperationFactory = new SvnOperationFactory()
        final ISVNAuthenticationManager authenticationManager = SVNWCUtil.createDefaultAuthenticationManager(System.properties['svnuser'], System.properties['svnpasswd'].toCharArray())
        try {
            svnOperationFactory.setAuthenticationManager(authenticationManager)
            final SvnCheckout checkout = svnOperationFactory.createCheckout()
            checkout.setSingleTarget(SvnTarget.fromFile(target))
            checkout.setSource(SvnTarget.fromURL(SVNURL.parseURIEncoded(source)))
            checkout.run()
        } finally {
            svnOperationFactory.dispose();
        }
    }

    protected void svnChangeTestFile(File target) {
        File propertyFile = new File(target, 'test.properties')
        def fileText = propertyFile.text
        fileText = (fileText =~ /testproperty *= *\S*/).replaceFirst("testproperty = ${(new Date()).toString()}")
        propertyFile.write(fileText)

        File newFile = new File(target, 'new.properties')
        newFile.write("changed")
    }

    protected void gitCheckOut(File target, String source, String branch) {
        CloneCommand cmd = Git.cloneRepository()
                .setURI(source)
                .setBranch(branch)
                .setDirectory(target)
                .setCredentialsProvider( new UsernamePasswordCredentialsProvider( System.properties['gituser'], System.properties['gitpasswd']) )
        cmd.call()
    }

    protected void gitTagCheckOut(File target, String source, String branch, String tag) {
        gitCheckOut(target, source, branch)
        Git git = getGit(target)

        FetchCommand fetch = git.fetch()
        fetch.remote = 'origin'
        fetch.setCheckFetchedObjects(true)
        fetch.setCredentialsProvider( new UsernamePasswordCredentialsProvider( System.properties['gituser'], System.properties['gitpasswd']) )
        fetch.call()

        CheckoutCommand checkout = git.checkout()
        checkout.setName("tags/${tag}")
        checkout.setStartPoint("tags/${tag}")
        checkout.call()
    }

    protected void gitChangeTestFile(File target) {
        File propertyFile = new File(target, 'test.properties')
        def fileText = propertyFile.text
        fileText = (fileText =~ /testproperty *= *\S*/).replaceFirst("testproperty = ${(new Date()).toString()}")
        propertyFile.write(fileText)

        File newFile = new File(target, 'new.properties')
        newFile.write("changed")
    }

    protected void svnRemove(String source) {
        SVNURL svnURL = null

        final ISVNAuthenticationManager authenticationManager = SVNWCUtil.createDefaultAuthenticationManager(System.properties['svnuser'], System.properties['svnpasswd'].toCharArray())
        final SVNRepository repo = SVNRepositoryFactory.create(SVNURL.parseURIEncoded(source), null)
        repo.setAuthenticationManager(authenticationManager)
        SVNNodeKind nodeKind = repo.checkPath( "" ,  -1 )

        if( nodeKind == SVNNodeKind.DIR || nodeKind == SVNNodeKind.FILE) {

            final SvnOperationFactory svnOperationFactory = new SvnOperationFactory()
            svnOperationFactory.setAuthenticationManager(authenticationManager)
            try {
                log.info('Start remote delete for {}', source)
                final SvnRemoteDelete remoteDelete = svnOperationFactory.createRemoteDelete()
                remoteDelete.setSingleTarget(SvnTarget.fromURL(SVNURL.parseURIEncoded(source)))
                remoteDelete.setCommitMessage('Remove after test')
                final SVNCommitInfo commitInfo = remoteDelete.run()
                if (commitInfo) {
                    final long newRevision = commitInfo.getNewRevision();
                    log.info('{} removed, revision {} created', source, newRevision)
                }
            } finally {
                svnOperationFactory.dispose();
            }
        }
    }

    protected void gitTagRemove(File projectDir, String tag) {
        Git git = getGit(projectDir)
        try {

            //delete tag locally
            git.branchDelete().setBranchNames("refs/tags/${tag}").call()

            //delete btag on remote 'origin'
            RefSpec refSpec = new RefSpec()
                    .setSource(null)
                    .setDestination("refs/tags/${tag}");
            git.push()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(System.properties['gituser'], System.properties['gitpasswd']))
                    .setRefSpecs(refSpec).setRemote('origin').call()
        } catch(Exception ex) {
            ex.printStackTrace()
        }
    }

    protected void gitBranchRemove(File projectDir, String branch) {
        Git git = getGit(projectDir)
        try {

            //delete tag locally
            git.branchDelete().setBranchNames("refs/heads/${branch}").call()

            //delete btag on remote 'origin'
            RefSpec refSpec = new RefSpec()
                    .setSource(null)
                    .setDestination("refs/heads/${branch}");
            git.push()
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(System.properties['gituser'], System.properties['gitpasswd']))
                    .setRefSpecs(refSpec).setRemote('origin').call()
        } catch(Exception ex) {
            ex.printStackTrace()
        }
    }

    private Git getGit(File dir) {
        Repository repo = new RepositoryBuilder()
                .readEnvironment()
                .findGitDir(dir)
                .build()
        Git git = new Git(repo)
    }
}