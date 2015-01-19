/*
 * Copyright 2015 Jin Kwon &lt;jinahya_at_gmail.com&gt;.
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


package com.github.jinahya.simple.file.front;


import static java.lang.invoke.MethodHandles.lookup;
import java.util.logging.Logger;
import static java.util.logging.Logger.getLogger;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.StatusType;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTestNg.ContainerPerClassTest;


/**
 *
 * @author Jin Kwon &lt;jinahya_at_gmail.com&gt;
 */
public class LocatorsResourceTest extends ContainerPerClassTest {


    private static final Logger logger
        = getLogger(lookup().lookupClass().getName());


    //@Test
    public void updateSingle() {

        //logger.log(Level.FINE, "target.uri: {0}", target().getUri().toString());

        final Response response = target().path("locators")
            .path("test")
            .request()
            .put(Entity.entity(new byte[0], MediaType.APPLICATION_OCTET_STREAM));
        final StatusType statusInfo = response.getStatusInfo();
        //logger.log(Level.FINE, "statusInfo: {}", statusInfo);
    }


    @Override
    protected Application configure() {

        //logger.log(Level.FINE, "configure()");
        System.out.println("configure()");

        final ResourceConfig resourceConfig = new ResourceConfig();

        resourceConfig.register(LocatorsResource.class);
        //resourceConfig.register(BackingBinder.class);
        //resourceConfig.register(SiblingsBinder.class);
        resourceConfig.register(new BackingBinder());
        resourceConfig.register(new SiblingsBinder());

        resourceConfig.getClasses().forEach(component -> {
            System.out.println("component: " + component);
        });

        return resourceConfig;
    }


}

