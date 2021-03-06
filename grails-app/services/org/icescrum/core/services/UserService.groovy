/*
 * Copyright (c) 2014 Kagilum SAS.
 *
 * This file is part of iceScrum.
 *
 * iceScrum is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * iceScrum is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with iceScrum.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Authors:
 *
 * Vincent Barrier (vbarrier@kagilum.com)
 * Nicolas Noullet (nnoullet@kagilum.com)
 */

package org.icescrum.core.services

import org.icescrum.core.domain.User
import org.icescrum.core.event.IceScrumEventPublisher
import org.icescrum.core.event.IceScrumEventType
import org.springframework.security.access.prepost.PreAuthorize
import org.icescrum.core.domain.preferences.UserPreferences
import org.springframework.transaction.annotation.Transactional
import org.apache.commons.io.FilenameUtils
import org.icescrum.core.utils.ImageConvert
import org.icescrum.core.support.ApplicationSupport

@Transactional
class UserService extends IceScrumEventPublisher {

    def grailsApplication
    def springSecurityService
    def burningImageService
    def notificationEmailService

    void save(User user) {
        if (!user.validate()){
            throw new RuntimeException()
        }
        user.password = springSecurityService.encodePassword(user.password)
        if (!user.save()) {
            throw new RuntimeException()
        }
        publishSynchronousEvent(IceScrumEventType.CREATE, user)
    }

    void update(User user, Map props) {

        if (props.pwd){
            user.password = springSecurityService.encodePassword(props.pwd)
        }
        if (props.preferences){
            user.preferences.emailsSettings = props.emailsSettings
        }
        try {
            if (props.avatar) {
                if (FilenameUtils.getExtension(props.avatar) != 'png') {
                    def oldAvatarPath = props.avatar
                    def newAvatarPath = props.avatar.replace(FilenameUtils.getExtension(props.avatar), 'png')
                    ImageConvert.convertToPNG(oldAvatarPath, newAvatarPath)
                    props.avatar = newAvatarPath
                }
                burningImageService.doWith(props.avatar, grailsApplication.config.icescrum.images.users.dir).execute(user.id.toString(), {
                    if (props.scale)
                        it.scaleAccurate(40, 40)
                })
            } else if(props.containsKey('avatar') && props.avatar == null) {
                def oldAvatar = new File(grailsApplication.config.icescrum.images.users.dir + user.id + '.png')
                if (oldAvatar.exists())
                    oldAvatar.delete()

            }
        }
        catch (RuntimeException e) {
            if (log.debugEnabled) e.printStackTrace()
            throw new RuntimeException('is.convert.image.error')
        }

        user.lastUpdated = new Date()

        def dirtyProperties = publishSynchronousEvent(IceScrumEventType.BEFORE_UPDATE, user)
        if (!user.save()) {
            throw new RuntimeException(user.errors?.toString())
        }
        publishSynchronousEvent(IceScrumEventType.UPDATE, user, dirtyProperties)
    }

    @PreAuthorize("ROLE_ADMIN")
    boolean delete(User user) {
        try {
            def dirtyProperties = publishSynchronousEvent(IceScrumEventType.BEFORE_DELETE, user)
            user.delete()
            publishSynchronousEvent(IceScrumEventType.DELETE, user, dirtyProperties)
            return true
        } catch (Exception e) {
            return false
        }
    }

    def resetPassword(User user) {
        def pool = ['a'..'z', 'A'..'Z', 0..9, '_'].flatten()
        Random rand = new Random(System.currentTimeMillis())
        def passChars = (0..10).collect { pool[rand.nextInt(pool.size())] }
        def password = passChars.join('')
        update(user, [pwd:password])
        notificationEmailService.sendNewPassword(user, password)
    }


    void menuBar(User user, String id, String position, boolean hidden) {
        def currentMenu
        if (hidden) {
            currentMenu = user.preferences.menuHidden
            if (!currentMenu.containsKey(id)) {
                currentMenu.put(id, (currentMenu.size() + 1).toString())
                if (user.preferences.menu.containsKey(id)) {
                    this.menuBar(user, id, user.preferences.menuHidden.size().toString(), true)
                }
                user.preferences.menu.remove(id)
            }
        }
        else {
            currentMenu = user.preferences.menu
            if (!currentMenu.containsKey(id)) {
                currentMenu.put(id, (currentMenu.size() + 1).toString())
                if (user.preferences.menuHidden.containsKey(id)) {
                    this.menuBar(user, id, user.preferences.menuHidden.size().toString(), true)
                }
                user.preferences.menuHidden.remove(id)
            }
        }
        def from = currentMenu.get(id)?.toInteger()
        from = from ?: 1
        def to = position.toInteger()

        if (from != to) {

            if (from > to) {
                currentMenu.entrySet().each {it ->
                    if (it.value.toInteger() >= to && it.value.toInteger() <= from && it.key != id) {
                        it.value = (it.value.toInteger() + 1).toString()
                    }
                    else if (it.key == id) {
                        it.value = position
                    }
                }
            }
            else {
                currentMenu.entrySet().each {it ->
                    if (it.value.toInteger() <= to && it.value.toInteger() >= from && it.key != id) {
                        it.value = (it.value.toInteger() - 1).toString()
                    }
                    else if (it.key == id) {
                        it.value = position
                    }
                }
            }
        }
        user.lastUpdated = new Date()
        if (!user.save()) {
            throw new RuntimeException()
        }
    }

    @Transactional(readOnly = true)
    def unMarshall(def user) {
        try {
            def u
            if (user.@uid.text())
                u = User.findByUid(user.@uid.text())
            else{
                u = ApplicationSupport.findUserUIDOldXMl(user,null,null)
            }
            if (!u) {
                u = new User(
                        lastName: user.lastName.text(),
                        firstName: user.firstName.text(),
                        username: user.username.text(),
                        email: user.email.text(),
                        password: user.password.text(),
                        enabled: user.enabled.text().toBoolean() ?: true,
                        accountExpired: user.accountExpired.text().toBoolean() ?: false,
                        accountLocked: user.accountLocked.text().toBoolean() ?: false,
                        passwordExpired: user.passwordExpired.text().toBoolean() ?: false,
                        accountExternal: user.accountExternal?.text()?.toBoolean() ?: false,
                        uid: user.@uid.text() ?: (user.username.text() + user.email.text()).encodeAsMD5()
                )

                def language = user.preferences.language.text()
                if (language == "en") {
                    def version = ApplicationSupport.findIceScrumVersionFromXml(user)
                    if (version == null || version < "R6#2") {
                        language = "en_US"
                    }
                }
                u.preferences = new UserPreferences(
                        language: language,
                        activity: user.preferences.activity.text(),
                        filterTask: user.preferences.filterTask.text(),
                        menu: user.preferences.menu.text(),
                        menuHidden: user.preferences.menuHidden.text(),
                        hideDoneState: user.preferences.hideDoneState.text()?.toBoolean() ?: false
                )
            }
            return u
        } catch (Exception e) {
            if (log.debugEnabled) e.printStackTrace()
            throw new RuntimeException(e)
        }
    }
}