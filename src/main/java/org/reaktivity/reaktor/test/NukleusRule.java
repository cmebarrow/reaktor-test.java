/**
 * Copyright 2016-2017 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.reaktor.test;

import static java.lang.String.valueOf;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.agrona.IoUtil.createEmptyFile;
import static org.junit.Assert.assertEquals;
import static org.reaktivity.nukleus.Configuration.COMMAND_BUFFER_CAPACITY_PROPERTY_NAME;
import static org.reaktivity.nukleus.Configuration.COUNTERS_BUFFER_CAPACITY_PROPERTY_NAME;
import static org.reaktivity.nukleus.Configuration.DIRECTORY_PROPERTY_NAME;
import static org.reaktivity.nukleus.Configuration.RESPONSE_BUFFER_CAPACITY_PROPERTY_NAME;
import static org.reaktivity.nukleus.Configuration.STREAMS_BUFFER_CAPACITY_PROPERTY_NAME;
import static org.reaktivity.nukleus.Configuration.THROTTLE_BUFFER_CAPACITY_PROPERTY_NAME;

import java.io.File;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.reaktivity.nukleus.Configuration;
import org.reaktivity.nukleus.Nukleus;
import org.reaktivity.nukleus.NukleusFactory;

public final class NukleusRule implements TestRule
{
    private final String[] names;
    private final Nukleus[] nuklei;
    private final Properties properties;

    private Configuration configuration;

    public NukleusRule(String... names)
    {
        this.names = requireNonNull(names);
        this.nuklei = new Nukleus[names.length];
        this.properties = new Properties();
    }

    public NukleusRule directory(String directory)
    {
        properties.setProperty(DIRECTORY_PROPERTY_NAME, directory);
        return this;
    }

    public NukleusRule commandBufferCapacity(int commandBufferCapacity)
    {
        properties.setProperty(COMMAND_BUFFER_CAPACITY_PROPERTY_NAME, valueOf(commandBufferCapacity));
        return this;
    }

    public NukleusRule responseBufferCapacity(int responseBufferCapacity)
    {
        properties.setProperty(RESPONSE_BUFFER_CAPACITY_PROPERTY_NAME, valueOf(responseBufferCapacity));
        return this;
    }

    public NukleusRule counterValuesBufferCapacity(int counterValuesBufferCapacity)
    {
        properties.setProperty(COUNTERS_BUFFER_CAPACITY_PROPERTY_NAME, valueOf(counterValuesBufferCapacity));
        return this;
    }

    public NukleusRule streamsBufferCapacity(int streamsBufferCapacity)
    {
        properties.setProperty(STREAMS_BUFFER_CAPACITY_PROPERTY_NAME, valueOf(streamsBufferCapacity));
        return this;
    }

    public NukleusRule throttleBufferCapacity(int throttleBufferCapacity)
    {
        properties.setProperty(THROTTLE_BUFFER_CAPACITY_PROPERTY_NAME, valueOf(throttleBufferCapacity));
        return this;
    }

    public NukleusRule streams(
        String nukleus,
        String source)
    {
        Configuration configuration = configuration();
        int streamsBufferCapacity = configuration.streamsBufferCapacity();
        int throttleBufferCapacity = configuration.throttleBufferCapacity();
        Path directory = configuration.directory();

        final File streams = directory.resolve(String.format("%s/streams/%s", nukleus, source)).toFile();
        final int length = streamsBufferCapacity + RingBufferDescriptor.TRAILER_LENGTH +
                throttleBufferCapacity + RingBufferDescriptor.TRAILER_LENGTH;

        createEmptyFile(streams.getAbsoluteFile(), length);

        return this;
    }

    public <T extends Nukleus> T lookup(Class<T> clazz)
    {
        for (Nukleus nukleus : nuklei)
        {
            if (clazz.isInstance(nukleus))
            {
                return clazz.cast(nukleus);
            }
        }

        throw new IllegalStateException("nukleus not found: " + clazz.getName());
    }

    private Configuration configuration()
    {
        if (configuration == null)
        {
            configuration = new Configuration(properties);
        }
        return configuration;
    }

    @Override
    public Statement apply(Statement base, Description description)
    {
        return new Statement()
        {
            @Override
            public void evaluate() throws Throwable
            {
                NukleusFactory factory = NukleusFactory.instantiate();
                Configuration config = configuration();
                final AtomicBoolean finished = new AtomicBoolean();
                final AtomicInteger errorCount = new AtomicInteger();
                final IdleStrategy idler = new BackoffIdleStrategy(64, 64, NANOSECONDS.toNanos(64L), MICROSECONDS.toNanos(64L));

                for (int i=0; i < names.length; i++)
                {
                    nuklei[i] = factory.create(names[i], config);
                }
                Runnable runnable = () ->
                {
                    while (!finished.get())
                    {
                        try
                        {
                            int workCount = 0;

                            for (int i=0; i < nuklei.length; i++)
                            {
                                workCount += nuklei[i].process();
                            }

                            idler.idle(workCount);
                        }
                        catch (Exception ex)
                        {
                            errorCount.incrementAndGet();
                            ex.printStackTrace(System.err);
                        }
                    }
                    try
                    {
                        for (int i=0; i < nuklei.length; i++)
                        {
                            nuklei[i].close();
                        }
                    }
                    catch (Exception ex)
                    {
                        errorCount.incrementAndGet();
                        ex.printStackTrace(System.err);
                    }
                };
                Thread caller = new Thread(runnable);
                try
                {
                    caller.start();

                    base.evaluate();
                }
                finally
                {
                    finished.set(true);
                    caller.join();
                    assertEquals(0, errorCount.get());
                }
            }
        };
    }
}
