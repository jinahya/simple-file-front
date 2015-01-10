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
import java.nio.channels.FileChannel;
import java.nio.file.Files;
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
                logger.error("failed to delete tempPath: " + tempPath, ioe);
            }
        }
    }


    @Produces(MediaType.WILDCARD)
    @GET
    @Path("/{path: .+}")
    public Response readSingle(@PathParam("path") final String path) {

        logger.debug("path: {}", path);

        final FileContext fileContext = new DefaultFileContext();

        fileContext.pathNameSupplier(() -> path);

        fileContext.targetChannelSupplier(() -> {
            try {
                tempPath = Files.createTempFile("prefix", "suffix");
                return FileChannel.open(tempPath, StandardOpenOption.READ,
                                        StandardOpenOption.WRITE);
            } catch (final IOException ioe) {
                throw new RuntimeException(ioe);
            }
        });

        final Holder<Long> targetCopiedHolder = new Holder<>();
        fileContext.targetCopiedConsumer(targetCopied -> {
            targetCopiedHolder.value(targetCopied);
        });

        try {
            fileBack.read(fileContext);
        } catch (IOException | FileBackException e) {
            throw new WebApplicationException(e);
        }

        if (targetCopiedHolder.value() == null) {
            throw new NotFoundException();
        }

        return Response.ok((StreamingOutput) output -> {
            Files.copy(tempPath, output);
        })
            .header("Content-Length", targetCopiedHolder.value())
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

