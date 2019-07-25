/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.server.modules;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;

import org.neo4j.configuration.Config;
import org.neo4j.server.CommunityNeoServer;
import org.neo4j.server.configuration.ServerSettings;
import org.neo4j.server.web.WebServer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ManagementApiModuleTest
{
    @SuppressWarnings( "unchecked" )
    @Test
    public void shouldRegisterASingleUri() throws Exception
    {
        WebServer webServer = mock( WebServer.class );

        CommunityNeoServer neoServer = mock( CommunityNeoServer.class );
        when( neoServer.baseUri() ).thenReturn( new URI( "http://localhost:7575" ) );
        when( neoServer.getWebServer() ).thenReturn( webServer );

        Config config = Config.defaults( ServerSettings.management_api_path, URI.create( "/db/manage" ) );

        when( neoServer.getConfig() ).thenReturn( config );

        ManagementApiModule module = new ManagementApiModule( webServer, config );
        module.start();

        ArgumentCaptor<List<Class<?>>> captor = ArgumentCaptor.forClass( List.class );
        verify( webServer ).addJAXRSClasses( captor.capture(), anyString(), any() );
        assertThat( captor.getValue(), not( empty() ) );
    }
}
