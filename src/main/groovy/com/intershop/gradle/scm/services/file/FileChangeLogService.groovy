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


package com.intershop.gradle.scm.services.file

import com.intershop.gradle.scm.extension.VersionExtension
import com.intershop.gradle.scm.services.ScmChangeLogService
import com.intershop.gradle.scm.services.ScmLocalService
import com.intershop.gradle.scm.utils.ScmKey
import com.intershop.gradle.scm.utils.ScmUser
import groovy.util.logging.Slf4j

@Slf4j
class FileChangeLogService extends FileRemoteService implements ScmChangeLogService{

    FileChangeLogService(ScmLocalService sls,
                         VersionExtension versionExt,
                         ScmUser user = null,
                         ScmKey key = null) {
        super(sls, user)
        type = sls.type
    }

    void createLog() {
        log.warn('This function is unsupported scm for the change log creation.')
    }
}