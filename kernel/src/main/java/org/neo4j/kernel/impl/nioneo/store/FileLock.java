/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.nioneo.store;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;

import org.neo4j.kernel.Config;

public abstract class FileLock
{
    private static FileLock wrapOrNull( final java.nio.channels.FileLock lock )
    {
        if ( lock == null )
        {
            return null;
        }
        
        return new FileLock()
        {
            @Override
            public void release() throws IOException
            {
                lock.release();
            }
        };
    }
    
    public static FileLock getOsSpecificFileLock( String fileName, FileChannel channel )
            throws IOException
    {
        if ( Config.osIsWindows() )
        {
            // Only grab one lock, say for the "neostore" file
            if ( fileName.endsWith( "neostore" ) )
            {
                return getWindowsFileLock( new File( fileName ).getParentFile() );
            }
            
            // For the rest just return placebo locks
            return new PlaceboFileLock();
        }
        else
        {
            return wrapOrNull( channel.tryLock() );
        }
    }
    
    private static FileLock getWindowsFileLock( File storeDir ) throws IOException
    {
        File lockFile = new File( storeDir, "lock" );
        if ( !lockFile.exists() )
        {
            if ( !lockFile.createNewFile() )
            {
                throw new IOException( "Couldn't create lock file " + lockFile.getAbsolutePath() );
            }
        }
        FileChannel fileChannel = new RandomAccessFile( lockFile, "rw" ).getChannel();
        java.nio.channels.FileLock fileChannelLock = null;
        try
        {
            fileChannelLock = fileChannel.tryLock(); 
        }
        catch ( OverlappingFileLockException e )
        {
            // OK, let fileChannelLock continue to be null and we'll deal with it below
        }
        if ( fileChannelLock == null )
        {
            fileChannel.close();
            return null;
        }
        return new WindowsFileLock( lockFile, fileChannel, fileChannelLock );
    }

    public abstract void release() throws IOException;
    
    private static class PlaceboFileLock extends FileLock
    {
        @Override
        public void release() throws IOException
        {
        }
    }
    
    private static class WindowsFileLock extends FileLock
    {
        private final File lockFile;
        private final FileChannel fileChannel;
        private final java.nio.channels.FileLock fileChannelLock;

        public WindowsFileLock( File lockFile, FileChannel fileChannel, java.nio.channels.FileLock lock )
                throws IOException
        {
            this.lockFile = lockFile;
            this.fileChannel = fileChannel;
            this.fileChannelLock = lock;
        }

        @Override
        public void release() throws IOException
        {
            try
            {
                fileChannelLock.release();
            }
            finally
            {
                try
                {
                    fileChannel.close();
                }
                finally
                {
                    if ( !lockFile.delete() )
                    {
                        throw new IOException( "Couldn't delete lock file " + lockFile.getAbsolutePath() );
                    }
                }
            }
        }
    }
}
