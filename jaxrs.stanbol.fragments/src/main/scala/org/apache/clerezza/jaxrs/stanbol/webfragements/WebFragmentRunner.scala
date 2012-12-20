/*
 * Copyright 2012 The Apache Software Foundation.
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
 * limitations under the License.
 */

package org.apache.clerezza.jaxrs.stanbol.webfragements

import org.apache.stanbol.commons.web.base.resource.BaseStanbolResource
import org.apache.wink.osgi.WinkRequestProcessor
import java.util.ArrayList
import java.util.Collections
import javax.servlet.FilterChain
import javax.servlet.Servlet
import javax.servlet.ServletContext
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServlet
import javax.ws.rs.ext.ContextResolver
import javax.ws.rs.ext.Provider
import org.apache.clerezza.osgi.services.ActivationHelper
import org.apache.felix.scr.annotations._
import org.apache.stanbol.commons.web.base.LinkResource
import org.apache.stanbol.commons.web.base.NavigationLink
import org.apache.stanbol.commons.web.base.ScriptResource
import org.apache.stanbol.commons.web.base.WebFragment
import org.osgi.framework.BundleContext
import org.osgi.service.component.ComponentContext
import org.slf4j.scala.Logging



@Component
@Reference(name = "webFragment", 
           referenceInterface = classOf[WebFragment], 
           cardinality = ReferenceCardinality.OPTIONAL_MULTIPLE, 
           policy = ReferencePolicy.DYNAMIC)
//@Service(Array(classOf[Servlet]))
//@Property(name = "alias", value = Array("/stanbol-ugly-servlet-context-initializer"))
@Service(value = Array(classOf[javax.servlet.Filter]))
@Property(name ="pattern", value=Array(".*"))
class WebFragmentRunner extends javax.servlet.Filter with Logging {

  @Reference
  private var winkRequestProcessor: WinkRequestProcessor = _
  
  @Property(name = "org.apache.stanbol.commons.web.alias", value = Array("/"))
  final val ALIAS_PROPERTY = "org.apache.stanbol.commons.web.alias";

  @Property(name = STATIC_RESOURCES_URL_ROOT_PROPERTY, value = Array("/static"))
  final val STATIC_RESOURCES_URL_ROOT_PROPERTY = "org.apache.stanbol.commons.web.static.url";

  /**
   * The origins allowed for multi-host requests
   */
  @Property(name = CORS_ORIGIN, cardinality = 100, value = Array("*"))
  final val CORS_ORIGIN = "org.apache.stanbol.commons.web.cors.origin";

  @Property(name = CORS_ACCESS_CONTROL_EXPOSE_HEADERS, cardinality = 100, value = Array("Location"))
  final val CORS_ACCESS_CONTROL_EXPOSE_HEADERS = "org.apache.stanbol.commons.web.cors.access_control_expose_headers";

  
  private var webFragments: List[WebFragment] = Nil
  private var bundleContext: BundleContext = _
  private var activator: Option[ActivationHelper] = None
  
  private val linkResources = new ArrayList[LinkResource]();
  private val scriptResources = new ArrayList[ScriptResource]();
  private val navigationLinks = new ArrayList[NavigationLink]();
  
  private var staticUrlRoot: String = _
  private var applicationAlias: String = _
  
  private var corsOrigins : java.util.Set[String] = _
  private var exposedHeaders : java.util.Set[String] = _
  private var contextResolverImpl : ContextResolverImpl = _



  @Activate
  def activate(c: ComponentContext) {
    staticUrlRoot = c.getProperties().get(
      STATIC_RESOURCES_URL_ROOT_PROPERTY).asInstanceOf[String]
    applicationAlias = c.getProperties().get(
      ALIAS_PROPERTY).asInstanceOf[String];
    {
      val values = c.getProperties().get(CORS_ORIGIN);
      values match {
        case s: String => corsOrigins =  Collections.singleton(s)
        case i: java.lang.Iterable[_] => {
            corsOrigins = new java.util.HashSet[String]();
            val iter = i.iterator
            while (iter.hasNext) {
              corsOrigins.add(iter.next().toString)
            };
        }
      }
    }
    {
      val values = c.getProperties().get(CORS_ACCESS_CONTROL_EXPOSE_HEADERS);
      values match {
        case s: String => exposedHeaders = Collections.singleton(s)
        case i: java.lang.Iterable[_] => {
            exposedHeaders = new java.util.HashSet[String]();
            val iter = i.iterator
            while (iter.hasNext) {
              exposedHeaders.add(iter.next().toString)
            }
        }
      }
    }
    synchronized {
      println("activating with "+webFragments);
      bundleContext = c.getBundleContext
      registerFragments()
    }
  }
  
  @Deactivate
  def deactivate(c: ComponentContext) {
    synchronized {
      unregisterClasses()
      activator.foreach(_.stop(c.getBundleContext))
      activator = None
    }
  }

  private def registerFragments() {
    activator = Some(new ActivationHelper {
      
      for (f <- webFragments) {
        import scala.collection.JavaConverters._
        for (s <- f.getJaxrsResourceSingletons.asScala) {
          //could alos direcly invoke wink as below
          registerRootResource(s)
          logger.info("Registered: "+s)
        }
        for (c <- f.getJaxrsResourceClasses.asScala) {
            winkRequestProcessor.bindComponentClass(c)
            logger.info("Registered class: "+c)
        }
      }
      start (bundleContext)
    })
  }
  
  private def unregisterClasses() {
    for (f <- webFragments) {
        import scala.collection.JavaConverters._
        for (c <- f.getJaxrsResourceClasses.asScala) {
            winkRequestProcessor.unbindComponentClass(c)
            logger.info("Unregistered class: "+c)
        }
      }
  }

  protected def bindWebFragment(f: WebFragment) {
    linkResources.addAll(f.getLinkResources());
    scriptResources.addAll(f.getScriptResources());
    navigationLinks.addAll(f.getNavigationLinks());
    synchronized {
      webFragments ::= f
      activator.foreach { a =>
        unregisterClasses();
        a.stop(bundleContext);
        registerFragments()
      }
    } 
  }

  protected def unbindWebFragment(f: WebFragment) {
    linkResources.removeAll(f.getLinkResources());
    scriptResources.removeAll(f.getScriptResources());
    navigationLinks.removeAll(f.getNavigationLinks());
    synchronized {
      webFragments = webFragments diff List(f)
      activator.foreach { a=>
        unregisterClasses()
        a.stop(bundleContext);
        registerFragments()
      }
    }
  }
  
  //all this servlet stuff is only needed to get the servlet context
  //override def init(config: javax.servlet.ServletConfig) {
  override def init(config: javax.servlet.FilterConfig) {
    val servletContext = config.getServletContext

    Collections.sort(linkResources);
    Collections.sort(scriptResources);
    Collections.sort(navigationLinks);
    servletContext.setAttribute(classOf[BundleContext].getName(), bundleContext);
    servletContext.setAttribute(BaseStanbolResource.ROOT_URL, applicationAlias);
    servletContext.setAttribute(BaseStanbolResource.STATIC_RESOURCES_ROOT_URL, staticUrlRoot);
    servletContext.setAttribute(BaseStanbolResource.LINK_RESOURCES, linkResources);
    servletContext.setAttribute(BaseStanbolResource.SCRIPT_RESOURCES, scriptResources);
    servletContext.setAttribute(BaseStanbolResource.NAVIGATION_LINKS, navigationLinks);
    servletContext.setAttribute(CORS_ORIGIN, corsOrigins);
    servletContext.setAttribute(CORS_ACCESS_CONTROL_EXPOSE_HEADERS, exposedHeaders);
    contextResolverImpl = new ContextResolverImpl(servletContext)
    winkRequestProcessor.bindComponent(contextResolverImpl)
  }
  
  override def doFilter(request: ServletRequest, response: ServletResponse,
			chain: FilterChain){
		chain.doFilter(request, response);
	}

	override def destroy() {
      winkRequestProcessor.unbindComponent(contextResolverImpl)
	}


}

@Provider
class ContextResolverImpl(servletContext: ServletContext) extends ContextResolver[ServletContext] {

    def getContext(clazz: Class[_]): ServletContext = {
        servletContext;
    }
}