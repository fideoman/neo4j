/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.causalclustering.helper;

import java.util.ArrayList;
import java.util.List;

import org.neo4j.function.ThrowingConsumer;

public class CompositeEnableable implements Enableable
{
    private final List<Enableable> enableables = new ArrayList<>();

    public void add( Enableable enableable )
    {
        enableables.add( enableable );
    }

    @Override
    public void enable()
    {
        doOperation( Enableable::enable, "Enable" );
    }

    @Override
    public void disable()
    {
        doOperation( Enableable::disable, "Disable" );
    }

    private void doOperation( ThrowingConsumer<Enableable,Throwable> operation, String description )
    {
        try ( ErrorHandler errorHandler = new ErrorHandler( description ) )
        {
            for ( Enableable enableable : enableables )
            {
                try
                {
                    operation.accept( enableable );
                }
                catch ( Throwable throwable )
                {
                    errorHandler.add( throwable );
                }
            }
        }
    }
}
