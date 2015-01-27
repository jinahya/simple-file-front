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
import javax.inject.Inject;
import javax.inject.Named;
import org.glassfish.hk2.api.Injectee;
import org.glassfish.hk2.api.InjectionResolver;
import org.glassfish.hk2.api.ServiceHandle;
import org.slf4j.Logger;
import static org.slf4j.LoggerFactory.getLogger;


/**
 *
 * @author Jin Kwon &lt;jinahya_at_gmail.com&gt;
 */
public class SiblingInjectionResolver implements InjectionResolver<Siblings> {


    @Override
    public Object resolve(final Injectee injectee,
                          final ServiceHandle<?> handle) {

        logger.debug("resolve({}, {})", injectee, handle);

        if (Siblings.class == injectee.getRequiredType()) {
            return systemInjectionResolver.resolve(injectee, handle);
        }

        return null;
    }


    @Override
    public boolean isConstructorParameterIndicator() {
        return false;
    }


    @Override
    public boolean isMethodParameterIndicator() {
        return false;
    }


    private transient final Logger logger = getLogger(lookup().lookupClass());


    @Inject
    @Named(InjectionResolver.SYSTEM_RESOLVER_NAME)
    private InjectionResolver<Inject> systemInjectionResolver;


}

