/*
 * Copyright 2014 Jin Kwon.
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


import com.github.jinahya.simple.file.back.DefaultFileContext;
import com.github.jinahya.simple.file.back.FileBack;
import com.github.jinahya.simple.file.back.FileBackException;
import com.github.jinahya.simple.file.back.FileContext;
import java.io.IOException;
import static java.lang.invoke.MethodHandles.lookup;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;
import org.slf4j.Logger;
import static org.slf4j.LoggerFactory.getLogger;


/**
 *
 * @author Jin Kwon &lt;jinahya_at_gmail.com&gt;
 */
//@Path("/paths")
public abstract class AbstractPathsResource {


    public static final String PREFERRED_PATH_VALUE = "/paths";


    @PostConstruct
    private void constructed() {
    }


    @PreDestroy
    private void destroying() {

        if (tempPath != null) {
            try {
                Files.deleteIfExists(tempPath);
            } catch (final IOException ioe) {
                logger.error("failed to delete temp path: " + tempPath, ioe);
            }
        }
    }


    @Produces(MediaType.WILDCARD)
    @GET
    @Path("/{path: .+}")
    public Response readSingle(@PathParam("path") final String path)
        throws IOException, FileBackException {

        logger.trace("path: {}", path);

        tempPath = Files.createTempFile("prefix", "suffix");

        final FileContext fileContext = new DefaultFileContext();

        fileContext.pathNameSupplier(() -> path);

        final Object[] sourceObject_ = new Object[1];
        fileContext.sourceObjectConsumer(sourceObject -> {
            logger.trace("source object: {}", sourceObject);
            sourceObject_[0] = sourceObject;
        });

        final Long[] sourceCopied_ = new Long[1];
        fileContext.sourceCopiedConsumer(sourceCopied -> {
            logger.trace("source copied: {}", sourceCopied);
            sourceCopied_[0] = sourceCopied;
        });

        final Long[] targetCopied_ = new Long[1];
        fileContext.targetCopiedConsumer(targetCopied -> {
            logger.trace("target copied: {}", targetCopied);
            targetCopied_[0] = targetCopied;
        });

        fileContext.sourceChannelConsumer(sourceChannel -> {
            logger.trace("source channel : {}", sourceChannel);
            try {
                final long sourceCopied = Files.copy(
                    Channels.newInputStream(sourceChannel), tempPath,
                    StandardCopyOption.REPLACE_EXISTING);
                logger.trace("source copied: {}", sourceCopied);
                sourceCopied_[0] = sourceCopied;
            } catch (final IOException ioe) {
                final String message
                    = "failed from source channel to temp path";
                logger.error(message, ioe);
                throw new WebApplicationException(message, ioe);
            }
        });

        final FileChannel[] targetChannel_ = new FileChannel[1];
        fileContext.targetChannelSupplier(true ? null : () -> { // _not_usd_!!!
            try {
                targetChannel_[0] = FileChannel.open(
                    tempPath, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
                logger.trace("target channel: {}", targetChannel_[0]);
                return targetChannel_[0];
            } catch (final IOException ioe) {
                final String message
                    = "failed to open temp path for writing";
                logger.error(message, ioe);
                throw new WebApplicationException(message, ioe);
            }
        });

        fileBack.operate(fileContext);

        if (sourceCopied_[0] == null) {
            throw new NotFoundException("no file for path: " + path);
        }

        return Response
            .ok((StreamingOutput) output -> Files.copy(tempPath, output))
            .header("Content-Length", sourceCopied_[0])
            .build();
    }


    private transient final Logger logger = getLogger(lookup().lookupClass());


    private transient java.nio.file.Path tempPath;


    /**
     * A file back injected.
     */
    @Inject
    @Backing
    private FileBack fileBack;


    @Context
    private UriInfo uriInfo;


    @HeaderParam("Content-Type")
    private MediaType contentType;


}

