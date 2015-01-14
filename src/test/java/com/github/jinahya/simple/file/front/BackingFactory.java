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


import com.github.jinahya.simple.file.back.FileBack;
import com.github.jinahya.simple.file.back.FileBackException;
import com.github.jinahya.simple.file.back.FileContext;
import java.io.IOException;
import static java.lang.invoke.MethodHandles.lookup;
import static java.util.Optional.ofNullable;
import org.glassfish.hk2.api.Factory;
import org.glassfish.jersey.process.internal.RequestScoped;
import static org.mockito.Matchers.any;
import org.mockito.Mockito;
import org.slf4j.Logger;
import static org.slf4j.LoggerFactory.getLogger;


/**
 *
 * @author Jin Kwon &lt;jinahya_at_gmail.com&gt;
 */
@RequestScoped
public class BackingFactory implements Factory<FileBack> {


    @Override
    public FileBack provide() {

        final FileBack fileBack = Mockito.mock(FileBack.class);
        try {
            Mockito.doAnswer(invocation -> {
                final FileContext fileContext
                    = invocation.getArgumentAt(0, FileContext.class);
                logger.debug("fileContext.fileOperation",
                             ofNullable(fileContext.fileOperationSupplier())
                             .orElse(() -> null).get());
                logger.debug("fileContext.sourceKey",
                             ofNullable(fileContext.sourceKeySupplier())
                             .orElse(() -> null).get());
                logger.debug("fileContext.sourceKey",
                             ofNullable(fileContext.sourceChannelConsumer())
                             .orElse(null));
                logger.debug("fileContext.sourceKey",
                             ofNullable(fileContext.sourceCopiedConsumer())
                             .orElse(null));
                logger.debug("fileContext.sourceKey",
                             ofNullable(fileContext.sourceKeySupplier())
                             .orElse(() -> null).get());
                return null;
            })
                .when(fileBack)
                .operate(any(FileContext.class));
        } catch (IOException | FileBackException e) {
            logger.error("failed to operation", e);
        }

        return fileBack;
    }


    @Override
    public void dispose(final FileBack instance) {

    }


    private transient final Logger logger = getLogger(lookup().lookupClass());


}

