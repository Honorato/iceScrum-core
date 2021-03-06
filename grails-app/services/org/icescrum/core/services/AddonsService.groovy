package org.icescrum.core.services

import org.icescrum.core.domain.Actor
import org.icescrum.core.domain.Feature
import org.icescrum.core.domain.Release
import org.icescrum.core.domain.Story
import org.icescrum.core.event.IceScrumProductEvent
import org.springframework.context.ApplicationListener
import org.icescrum.core.domain.Product
import org.icescrum.core.domain.User
import grails.plugin.fluxiable.Activity
import grails.plugin.fluxiable.ActivityException
import grails.plugin.fluxiable.ActivityLink
import grails.util.GrailsNameUtils
import java.text.SimpleDateFormat
import org.grails.comments.Comment
import org.grails.comments.CommentException
import org.grails.comments.CommentLink
import org.icescrum.core.domain.Task

class AddonsService implements ApplicationListener<IceScrumProductEvent> {

    @Override
    void onApplicationEvent(IceScrumProductEvent e) {
        if (e.type == IceScrumProductEvent.EVENT_IMPORTED){
            //Wait a small very small time to let hibernate do its job... well
            Thread.sleep(500);
            synchronisedDataImport(e)
        }
    }

    void synchronisedDataImport(IceScrumProductEvent e){
        Product p = (Product) e.source
        addTags(p, e.xml)
        addDependsOn(p, e.xml)
        addComments(p, e.xml)
        addActivities(p, e.xml)
        addAttachments(p, e.xml, e.importPath)
    }

    void addTags(Product p, def root) {
        if (log.debugEnabled)
            log.debug("start import tags")
        root.'**'.findAll{ it.name() in ["story","actor","task","feature"] }.each{ element ->
            def elemt = null
            def tasksCache = []
            if (element.tags.text()){
                switch(element.name()){
                    case 'story':
                        elemt = Story.findByBacklogAndUid(p, element.@uid.text().toInteger())?:null
                        break
                    case 'actor':
                        elemt = Actor.findByBacklogAndUid(p, element.@uid.text().toInteger())?:null
                        break
                    case 'task':
                        tasksCache = tasksCache ?: Task.getAllInProduct(p.id)
                        elemt = tasksCache?.find { it.uid == element.@uid.text().toInteger() } ?: null
                        break
                    case 'feature':
                        elemt = Feature.findByBacklogAndUid(p, element.@uid.text().toInteger())?:null
                        break
                }
            }
            if (elemt){
                elemt.tags = element.tags.text().replaceAll(' ','').replace('[','').replace(']','').split(',')
            }
        }
        if (log.debugEnabled)
            log.debug("end import tags")
    }

    void addDependsOn(Product p, def root) {
        if (log.debugEnabled)
            log.debug("start import dependsOn")
        root.'**'.findAll{ it.name() == "story" }.each{ element ->
            if (!element.dependsOn?.@uid?.isEmpty() && p) {
                def dependsOn = Story.findByBacklogAndUid(p, element.dependsOn.@uid.text().toInteger())
                def story = Story.findByBacklogAndUid(p, element.@uid.text().toInteger())
                if (dependsOn) {
                    story.dependsOn = dependsOn
                    dependsOn.lastUpdated = new Date()
                    story.lastUpdated = new Date()
                    story.save()
                    dependsOn.save()
                }
            }
        }
        if (log.debugEnabled)
            log.debug("end import dependsOn")
    }

    void addAttachments(Product p, def root, File importPath){
        def defaultU = p.productOwners.first()
        def tasksCache = []
        def sprintsCache = []
        if (log.debugEnabled)
            log.debug("start import files")
        root.'**'.findAll{ it.name() in ["story","actor","task","feature", "release", "sprint", "product"] }.each{ element ->
            def elemt = null
            if (element.attachments.attachment.text()){
                switch(element.name()){
                    case 'story':
                        elemt = Story.findByBacklogAndUid(p, element.@uid.text().toInteger())?:null
                        break
                    case 'actor':
                        elemt = Actor.findByBacklogAndUid(p, element.@uid.text().toInteger())?:null
                        break
                    case 'task':
                        tasksCache = tasksCache ?: Task.getAllInProduct(p.id)
                        elemt = tasksCache?.find { it.uid == element.@uid.text().toInteger() } ?: null
                        break
                    case 'feature':
                        elemt = Feature.findByBacklogAndUid(p, element.@uid.text().toInteger())?:null
                        break
                    case 'release':
                        elemt = Release.findByParentProductAndOrderNumber(p, element.orderNumber.text().toInteger())?:null
                        break
                    case 'sprint':
                        sprintsCache = sprintsCache ?: Release.findAllByParentProduct(p)*.sprints.flatten()
                        elemt = sprintsCache.find {
                            it.orderNumber == element.orderNumber.text().toInteger() && it.startDate.format(('yyyy-MM-dd HH:mm:ss')) == element.startDate.text()
                        } ?: null
                        break
                    case 'product' :
                        elemt = p
                        break
                }
            }
            if (elemt){
                element.attachments.attachment.each{ attachment ->
                    def u = root.'**'.find{ it.id.text() == attachment.posterId.text() &&  it.@uid.text() }
                    if(u){
                        u = User.findByUid(u.@uid.text())
                    }else if(defaultU){
                        u = defaultU
                    }
                    def originalName = attachment.inputName.text()
                    if (!attachment.url?.text()){
                        def path = "${importPath.absolutePath}${File.separator}attachments${File.separator}${attachment.@id.text()}.${attachment.ext.text()}"
                        def fileAttch = new File(path)
                        if (fileAttch.exists()){
                            elemt.addAttachment(u, fileAttch, originalName)
                        }
                    }else{
                        elemt.addAttachment(u, [filename:originalName, url:attachment.url.text(), provider:attachment.provider.text(), size:attachment.length.toInteger()])
                    }
                }
            }
        }
        if (log.debugEnabled)
            log.debug("end import files")
    }
    
    void addComments(Product p, def root){
        def defaultU = p.productOwners.first()
        if (log.debugEnabled)
            log.debug("start import comments")
        root.'**'.findAll{ it.name() == "story" }.each{ story ->
            def s = null
            if (story.comments.comment.text()){
                s = Story.findByBacklogAndUid(p, story.@uid.text().toInteger())?:null
            }
            if (s){
                story.comments.comment.each{ comment ->
                    def u = root.'**'.find{ it.id.text() == comment.posterId.text() &&  it.@uid.text() }
                    if(u){
                       u = User.findByUid(u.@uid.text())
                    }else if(defaultU){
                       u = defaultU
                    }
                    addComment(s,
                        u,
                        comment.body.text(),
                        new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(comment.dateCreated.text()))
                }
            }
        }
        def tasksCache = []
        root.'**'.findAll{ it.name() == "task" }.each{ task ->
            def t = null
            if (task.comments.comment.text()){
                tasksCache = tasksCache ?: Task.getAllInProduct(p.id)
                t = tasksCache?.find { it.uid == task.@uid.text().toInteger() } ?: null
            }
            if (t){
                task.comments.comment.each{ comment ->
                    def u = root.'**'.find{ it.id.text() == comment.posterId.text() &&  it.@uid.text() }
                    if(u){
                        u = User.findByUid(u.@uid.text())
                    }else if(defaultU){
                        u = defaultU
                    }
                    addComment(t,
                            u,
                            comment.body.text(),
                            new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(comment.dateCreated.text()))
                }
            }
        }
        if (log.debugEnabled)
            log.debug("end import comments")
    }

    void addActivities(Product p, def root){
        def defaultU = p.productOwners.first()
        if (log.debugEnabled)
            log.debug("start import activities")
        root.'**'.findAll{ it.name() == "story" }.each{ story ->
            def s = null
            if (story.activities.activity.text()){
                s = Story.findByBacklogAndUid(p, story.@uid.text().toInteger())?:null
            }
            if (s){
                story.activities.activity.each{ activity ->
                    def u = root.'**'.find{ it.id.text() == activity.posterId.text() &&  it.@uid.text() }
                    if(u){
                        u = User.findByUid(u.@uid.text())
                    }else if(defaultU){
                        u = defaultU
                    }
                    addActivity(s,
                        u,
                        activity.code.text(),
                        activity.cachedLabel.text(),
                        new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(activity.dateCreated.text()),
                        activity.cachedDescription.text())
                }
            }
        }
        root.activities.activity.each{ activity ->
            def u = root.'**'.find{ it.id.text() == activity.posterId.text() &&  it.@uid.text() }
            if(u){
                u = User.findByUid(u.@uid.text())
            }else{
                u = defaultU
            }
            addActivity(p,
                        u,
                        activity.code.text(),
                        activity.cachedLabel.text(),
                        new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(activity.dateCreated.text()),
                        activity.cachedDescription.text())
        }

        if (log.debugEnabled)
            log.debug("end import activities")
    }

    private addActivity(def object, User poster, String code, String label, Date dateCreated, String desc){
        def posterClass = poster.class.name
        def i = posterClass.indexOf('_$$_javassist')
        if (i > -1)
          posterClass = posterClass[0..i - 1]

        def c = new Activity(code: code,
                                cachedLabel: label,
                                posterId: poster.id,
                                posterClass: posterClass,
                                cachedId: object.id,
                                cachedDescription: desc)
        if (!c.validate()) {
          throw new ActivityException("Cannot create activity for arguments [$poster, $code, $label], they are invalid.")
        }
        c.save()
        def delegateClass = object.class.name
        i = delegateClass.indexOf('_$$_javassist')
        if (i > -1) delegateClass = delegateClass[0..i - 1]
        def link = new ActivityLink(activity: c, activityRef: object.id, type: GrailsNameUtils.getPropertyName(delegateClass))
        link.save()
        c.dateCreated = dateCreated
    }

    private addComment(def object, User poster, String body, Date dateCreated){
        def posterClass = poster.class.name
        def i = posterClass.indexOf('_$$_javassist')
        if(i>-1)
            posterClass = posterClass[0..i-1]
        def c = new Comment(body:body, posterId:poster.id, posterClass:posterClass)
        if(!c.validate()) {
            throw new CommentException("Cannot create comment for arguments [$poster, $body], they are invalid.")
        }
        c.save()
        def link = new CommentLink(comment:c, commentRef:object.id, type:GrailsNameUtils.getPropertyName(object.class))
        link.save()
        c.dateCreated = dateCreated
    }

    private addAttachment(def object, User poster, String code, String label, Date dateCreated, String desc){
        def posterClass = poster.class.name
        def i = posterClass.indexOf('_$$_javassist')
        if (i > -1)
            posterClass = posterClass[0..i - 1]

        def c = new Activity(code: code,
                cachedLabel: label,
                posterId: poster.id,
                posterClass: posterClass,
                cachedId: object.id,
                cachedDescription: desc)
        if (!c.validate()) {
            throw new ActivityException("Cannot create activity for arguments [$poster, $code, $label], they are invalid.")
        }
        c.save()
        def delegateClass = object.class.name
        i = delegateClass.indexOf('_$$_javassist')
        if (i > -1) delegateClass = delegateClass[0..i - 1]
        def link = new ActivityLink(activity: c, activityRef: object.id, type: GrailsNameUtils.getPropertyName(delegateClass))
        link.save()
        c.dateCreated = dateCreated
    }
}
