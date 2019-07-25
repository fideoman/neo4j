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
package org.neo4j.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.util.function.LongSupplier;

import org.neo4j.collection.Dependencies;
import org.neo4j.common.DependencyResolver;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.io.pagecache.tracing.cursor.context.VersionContext;
import org.neo4j.kernel.impl.context.TransactionVersionContext;
import org.neo4j.kernel.impl.context.TransactionVersionContextSupplier;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.storageengine.api.TransactionIdStore;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.TestDirectoryExtension;
import org.neo4j.test.rule.TestDirectory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;

@ExtendWith( {TestDirectoryExtension.class} )
class QueryRestartIT
{
    @Inject
    private TestDirectory testDirectory;
    private GraphDatabaseService database;
    private TestTransactionVersionContextSupplier testContextSupplier;
    private File storeDir;
    private TestVersionContext testCursorContext;
    private DatabaseManagementService managementService;

    @BeforeEach
    void setUp()
    {
        storeDir = testDirectory.directory();
        testContextSupplier = new TestTransactionVersionContextSupplier();
        database = startSnapshotQueryDb();
        createData();

        testCursorContext = testCursorContext();
        testContextSupplier.setCursorContext( testCursorContext );
    }

    @AfterEach
    void tearDown()
    {
        if ( managementService != null )
        {
            managementService.shutdown();
        }
    }

    @Test
    void executeQueryWithoutRestarts()
    {
        testCursorContext.setWrongLastClosedTxId( false );

        Result result = database.execute( "MATCH (n:label) RETURN n.c" );
        while ( result.hasNext() )
        {
            assertEquals( "d", result.next().get( "n.c" ) );
        }
        assertEquals( 0, testCursorContext.getAdditionalAttempts() );
    }

    @Test
    void executeQueryWithSingleRetry()
    {
        Result result = database.execute( "MATCH (n) RETURN n.c" );
        assertEquals( 1, testCursorContext.getAdditionalAttempts() );
        while ( result.hasNext() )
        {
            assertEquals( "d", result.next().get( "n.c" ) );
        }
    }

    @Test
    void executeCountStoreQueryWithSingleRetry()
    {
        Result result = database.execute( "MATCH (n:toRetry) RETURN count(n)" );
        assertEquals( 1, testCursorContext.getAdditionalAttempts() );
        while ( result.hasNext() )
        {
            assertEquals( 1L, result.next().get( "count(n)" ) );
        }
    }

    @Test
    void executeLabelScanQueryWithSingleRetry()
    {
        Result result = database.execute( "MATCH (n:toRetry) RETURN n.c" );
        assertEquals( 1, testCursorContext.getAdditionalAttempts() );
        while ( result.hasNext() )
        {
            assertEquals( "d", result.next().get( "n.c" ) );
        }
    }

    @Test
    void queryThatModifyDataAndSeeUnstableSnapshotThrowException()
    {
        try
        {
            database.execute( "MATCH (n:toRetry) CREATE () RETURN n.c" );
        }
        catch ( QueryExecutionException e )
        {
            assertEquals( "Unable to get clean data snapshot for query " +
                    "'MATCH (n:toRetry) CREATE () RETURN n.c' that perform updates.", e.getMessage() );
        }
    }

    private GraphDatabaseService startSnapshotQueryDb()
    {
        // Inject TransactionVersionContextSupplier
        Dependencies dependencies = new Dependencies();
        dependencies.satisfyDependencies( testContextSupplier );

        managementService = new TestDatabaseManagementServiceBuilder( storeDir )
                .setExternalDependencies( dependencies )
                .setConfig( GraphDatabaseSettings.snapshot_query, true )
                .build();
        return managementService.database( DEFAULT_DATABASE_NAME );
    }

    private void createData()
    {
        Label label = Label.label( "toRetry" );
        try ( Transaction transaction = database.beginTx() )
        {
            Node node = database.createNode( label );
            node.setProperty( "c", "d" );
            transaction.success();
        }
    }

    private TestVersionContext testCursorContext()
    {
        TransactionIdStore transactionIdStore = getTransactionIdStore();
        return new TestVersionContext( transactionIdStore::getLastClosedTransactionId );
    }

    private TransactionIdStore getTransactionIdStore()
    {
        DependencyResolver dependencyResolver = ((GraphDatabaseAPI) database).getDependencyResolver();
        return dependencyResolver.resolveDependency( TransactionIdStore.class );
    }

    private class TestVersionContext extends TransactionVersionContext
    {

        private boolean wrongLastClosedTxId = true;
        private int additionalAttempts;

        TestVersionContext( LongSupplier transactionIdSupplier )
        {
            super( transactionIdSupplier );
        }

        @Override
        public long lastClosedTransactionId()
        {
            return wrongLastClosedTxId ? TransactionIdStore.BASE_TX_ID : super.lastClosedTransactionId();
        }

        @Override
        public void markAsDirty()
        {
            super.markAsDirty();
            wrongLastClosedTxId = false;
        }

        void setWrongLastClosedTxId( boolean wrongLastClosedTxId )
        {
            this.wrongLastClosedTxId = wrongLastClosedTxId;
        }

        @Override
        public boolean isDirty()
        {
            boolean dirty = super.isDirty();
            if ( dirty )
            {
                additionalAttempts++;
            }
            return dirty;
        }

        int getAdditionalAttempts()
        {
            return additionalAttempts;
        }
    }

    private class TestTransactionVersionContextSupplier extends TransactionVersionContextSupplier
    {
        void setCursorContext( VersionContext versionContext )
        {
            this.cursorContext.set( versionContext );
        }
    }
}
