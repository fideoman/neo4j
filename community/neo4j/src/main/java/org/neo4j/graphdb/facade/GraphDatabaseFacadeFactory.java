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
package org.neo4j.graphdb.facade;

import java.io.File;
import java.util.Map;
import java.util.function.Function;

import org.neo4j.bolt.BoltServer;
import org.neo4j.collection.Dependencies;
import org.neo4j.common.DependencyResolver;
import org.neo4j.common.Edition;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.database.DatabaseContext;
import org.neo4j.dbms.database.DatabaseManagementService;
import org.neo4j.dbms.database.DatabaseManagementServiceImpl;
import org.neo4j.dbms.database.DatabaseManager;
import org.neo4j.dbms.database.UnableToStartDatabaseException;
import org.neo4j.exceptions.KernelException;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.factory.module.DatabaseModule;
import org.neo4j.graphdb.factory.module.GlobalModule;
import org.neo4j.graphdb.factory.module.edition.AbstractEditionModule;
import org.neo4j.graphdb.spatial.Geometry;
import org.neo4j.graphdb.spatial.Point;
import org.neo4j.internal.collector.DataCollector;
import org.neo4j.internal.kernel.api.security.SecurityContext;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.impl.fulltext.FulltextAdapter;
import org.neo4j.kernel.api.procedure.Context;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.api.security.provider.SecurityProvider;
import org.neo4j.kernel.availability.StartupWaiter;
import org.neo4j.kernel.database.DatabaseId;
import org.neo4j.kernel.impl.api.dbms.NonTransactionalDbmsOperations;
import org.neo4j.kernel.impl.factory.DatabaseInfo;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.kernel.impl.pagecache.PublishPageCacheTracerMetricsAfterStart;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.internal.Version;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.logging.Log;
import org.neo4j.logging.internal.LogService;
import org.neo4j.procedure.ProcedureTransaction;
import org.neo4j.procedure.builtin.SpecialBuiltInProcedures;
import org.neo4j.procedure.impl.GlobalProceduresRegistry;
import org.neo4j.procedure.impl.ProcedureConfig;
import org.neo4j.procedure.impl.ProcedureTransactionProvider;
import org.neo4j.procedure.impl.TerminationGuardProvider;
import org.neo4j.values.storable.PointValue;
import org.neo4j.values.virtual.NodeValue;
import org.neo4j.values.virtual.PathValue;
import org.neo4j.values.virtual.RelationshipValue;

import static org.neo4j.configuration.GraphDatabaseSettings.SYSTEM_DATABASE_NAME;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTGeometry;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTNode;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTPath;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTPoint;
import static org.neo4j.internal.kernel.api.procs.Neo4jTypes.NTRelationship;

/**
 * This is the main factory for creating database instances. It delegates creation to three different modules
 * ({@link GlobalModule}, {@link AbstractEditionModule}, and {@link DatabaseModule}),
 * which create all the specific services needed to run a graph database.
 * <p>
 * To create test versions of databases, override an edition factory (e.g. {@link org.neo4j.kernel.impl.factory
 * .CommunityFacadeFactory}), and replace modules
 * with custom versions that instantiate alternative services.
 */
public class GraphDatabaseFacadeFactory
{

    protected final DatabaseInfo databaseInfo;
    private final Function<GlobalModule,AbstractEditionModule> editionFactory;

    public GraphDatabaseFacadeFactory( DatabaseInfo databaseInfo, Function<GlobalModule,AbstractEditionModule> editionFactory )
    {
        this.databaseInfo = databaseInfo;
        this.editionFactory = editionFactory;
    }

    /**
     * Instantiate a graph database given configuration and dependencies.
     *
     * @param storeDir the directory where the Neo4j data store is located
     * @param config configuration
     * @param dependencies the dependencies required to construct the {@link GraphDatabaseFacade}
     * @return the newly constructed {@link GraphDatabaseFacade}
     */
    public DatabaseManagementService newFacade( File storeDir, Config config, final ExternalDependencies dependencies )
    {
        return initFacade( storeDir, config, dependencies, new GraphDatabaseFacade() );
    }

    /**
     * Instantiate a graph database given configuration, dependencies, and a custom implementation of {@link org
     * .neo4j.kernel.impl.factory.GraphDatabaseFacade}.
     *
     * @param storeDir the directory where the Neo4j data store is located
     * @param params configuration parameters
     * @param dependencies the dependencies required to construct the {@link GraphDatabaseFacade}
     * @param graphDatabaseFacade the already created facade which needs initialisation
     * @return the initialised {@link GraphDatabaseFacade}
     */
    public DatabaseManagementService initFacade( File storeDir, Map<String,String> params, final ExternalDependencies dependencies,
            final GraphDatabaseFacade graphDatabaseFacade )
    {
        return initFacade( storeDir, Config.defaults( params ), dependencies, graphDatabaseFacade );
    }

    /**
     * Instantiate a graph database given configuration, dependencies, and a custom implementation of {@link org
     * .neo4j.kernel.impl.factory.GraphDatabaseFacade}.
     *
     * @param storeDir the directory where the Neo4j data store is located
     * @param config configuration
     * @param dependencies the dependencies required to construct the {@link GraphDatabaseFacade}
     * @param graphDatabaseFacade the already created facade which needs initialisation
     * @return the initialised {@link GraphDatabaseFacade}
     */
    public DatabaseManagementService initFacade( File storeDir, Config config, final ExternalDependencies dependencies,
            final GraphDatabaseFacade graphDatabaseFacade )
    {
        GlobalModule globalModule = createGlobalModule( storeDir, config, dependencies );
        AbstractEditionModule edition = editionFactory.apply( globalModule );
        Dependencies globalDependencies = globalModule.getGlobalDependencies();
        LifeSupport globalLife = globalModule.getGlobalLife();

        LogService logService = globalModule.getLogService();
        Log internalLog = logService.getInternalLog( getClass() );
        DatabaseManager<?> databaseManager = createAndInitializeDatabaseManager( globalModule, edition, graphDatabaseFacade, internalLog );
        DatabaseManagementService managementService =
                new DatabaseManagementServiceImpl( databaseManager, globalModule.getGlobalAvailabilityGuard(), globalLife, internalLog );

        GlobalProcedures globalProcedures = setupProcedures( globalModule, edition, databaseManager );
        globalDependencies.satisfyDependency( new NonTransactionalDbmsOperations( globalProcedures ) );

        edition.createSecurityModule( globalModule );
        SecurityProvider securityProvider = edition.getSecurityProvider();
        globalDependencies.satisfyDependencies( securityProvider.authManager() );
        globalDependencies.satisfyDependencies( securityProvider.userManagerSupplier() );

        globalLife.add( globalModule.getGlobalExtensions() );
        globalLife.add( createBoltServer( globalModule, edition, databaseManager ) );
        globalDependencies.satisfyDependency( edition.globalTransactionCounter() );
        globalLife.add( new StartupWaiter( globalModule.getGlobalAvailabilityGuard(), edition.getTransactionStartTimeout() ) );
        globalLife.add( new PublishPageCacheTracerMetricsAfterStart( globalModule.getTracers().getPageCursorTracerSupplier() ) );

        startDatabaseServer( config, globalModule, edition, globalLife, internalLog, databaseManager, managementService );

        return managementService;
    }

    private void startDatabaseServer( Config config, GlobalModule globalModule, AbstractEditionModule edition, LifeSupport globalLife, Log internalLog,
            DatabaseManager<?> databaseManager, DatabaseManagementService managementService )
    {
        RuntimeException error = null;
        try
        {
            edition.createDatabases( databaseManager, config );
            globalLife.start();
            verifySystemDatabaseStart( databaseManager );
        }
        catch ( Throwable throwable )
        {
            String message = "Error starting database server at " + globalModule.getStoreLayout().storeDirectory();
            error = new RuntimeException( message, throwable );
            internalLog.error( message, throwable );
        }
        finally
        {
            if ( error != null )
            {
                try
                {
                    managementService.shutdown();
                }
                catch ( Throwable shutdownError )
                {
                    error.addSuppressed( shutdownError );
                }
            }
        }

        if ( error != null )
        {
            internalLog.error( "Failed to start database server.", error );
            throw error;
        }
    }

    private static void verifySystemDatabaseStart( DatabaseManager<?> databaseManager )
    {
        DatabaseContext systemContext = databaseManager.getDatabaseContext( new DatabaseId( SYSTEM_DATABASE_NAME ) ).get();
        if ( systemContext.isFailed() )
        {
            throw new UnableToStartDatabaseException( SYSTEM_DATABASE_NAME + " failed to start.", systemContext.failureCause() );
        }
    }

    /**
     * Create the platform module. Override to replace with custom module.
     */
    protected GlobalModule createGlobalModule( File storeDir, Config config, final ExternalDependencies dependencies )
    {
        return new GlobalModule( storeDir, config, databaseInfo, dependencies );
    }

    /**
     * Creates and registers the systems procedures, including those which belong to a particular edition.
     * N.B. This method takes a {@link DatabaseManager} as an unused parameter *intentionally*, in
     * order to enforce that the databaseManager must be constructed first.
     */
    @SuppressWarnings( "unused" )
    private static GlobalProcedures setupProcedures( GlobalModule globalModule, AbstractEditionModule editionModule,
            DatabaseManager<?> databaseManager )
    {
        Config globalConfig = globalModule.getGlobalConfig();
        File proceduresDirectory = globalConfig.get( GraphDatabaseSettings.plugin_dir );
        LogService logService = globalModule.getLogService();
        Log internalLog = logService.getInternalLog( GlobalProcedures.class );
        Log proceduresLog = logService.getUserLog( GlobalProcedures.class );

        ProcedureConfig procedureConfig = new ProcedureConfig( globalConfig );
        Edition neo4jEdition = globalModule.getDatabaseInfo().edition;
        SpecialBuiltInProcedures builtInProcedures = new SpecialBuiltInProcedures( Version.getNeo4jVersion(), neo4jEdition.toString() );
        GlobalProceduresRegistry globalProcedures = new GlobalProceduresRegistry( builtInProcedures, proceduresDirectory, internalLog, procedureConfig );

        globalProcedures.registerType( Node.class, NTNode );
        globalProcedures.registerType( NodeValue.class, NTNode );
        globalProcedures.registerType( Relationship.class, NTRelationship );
        globalProcedures.registerType( RelationshipValue.class, NTRelationship );
        globalProcedures.registerType( Path.class, NTPath );
        globalProcedures.registerType( PathValue.class, NTPath );
        globalProcedures.registerType( Geometry.class, NTGeometry );
        globalProcedures.registerType( Point.class, NTPoint );
        globalProcedures.registerType( PointValue.class, NTPoint );

        // Below components are not public API, but are made available for internal
        // procedures to call, and to provide temporary workarounds for the following
        // patterns:
        //  - Batch-transaction imports (GDAPI, needs to be real and passed to background processing threads)
        //  - Group-transaction writes (same pattern as above, but rather than splitting large transactions,
        //                              combine lots of small ones)
        //  - Bleeding-edge performance (KernelTransaction, to bypass overhead of working with Core API)
        globalProcedures.registerComponent( DependencyResolver.class, Context::dependencyResolver, false );
        globalProcedures.registerComponent( KernelTransaction.class, Context::kernelTransaction, false );
        globalProcedures.registerComponent( GraphDatabaseAPI.class, Context::graphDatabaseAPI, false );

        // Register injected public API components
        globalProcedures.registerComponent( Log.class, ctx -> proceduresLog, true );
        globalProcedures.registerComponent( ProcedureTransaction.class, new ProcedureTransactionProvider(), true );
        globalProcedures.registerComponent( org.neo4j.procedure.TerminationGuard.class, new TerminationGuardProvider(), true );
        globalProcedures.registerComponent( SecurityContext.class, Context::securityContext, true );
        globalProcedures.registerComponent( FulltextAdapter.class, ctx -> ctx.dependencyResolver().resolveDependency( FulltextAdapter.class ), true );
        globalProcedures.registerComponent( DataCollector.class, ctx -> ctx.dependencyResolver().resolveDependency( DataCollector.class ), false );

        // Edition procedures
        try
        {
            editionModule.registerProcedures( globalProcedures, procedureConfig, globalModule, databaseManager );
        }
        catch ( KernelException e )
        {
            internalLog.error( "Failed to register built-in edition procedures at start up: " + e.getMessage() );
        }

        globalModule.getGlobalLife().add( globalProcedures );
        globalModule.getGlobalDependencies().satisfyDependency( globalProcedures );
        return globalProcedures;
    }

    private static BoltServer createBoltServer( GlobalModule platform, AbstractEditionModule edition,
            DatabaseManager<?> databaseManager )
    {
        return new BoltServer( databaseManager, platform.getJobScheduler(), platform.getConnectorPortRegister(), edition.getConnectionTracker(),
                platform.getGlobalConfig(), platform.getGlobalClock(), platform.getGlobalMonitors(), platform.getLogService(),
                platform.getGlobalDependencies() );
    }

    private static DatabaseManager<?> createAndInitializeDatabaseManager( GlobalModule platform,
            AbstractEditionModule edition, GraphDatabaseFacade facade, Log log )
    {
        DatabaseManager<?> databaseManager = edition.createDatabaseManager( facade, platform, log );
        if ( !edition.handlesDatabaseManagerLifecycle() )
        {
            // only add database manager to the lifecycle when edition doesn't manage it already
            platform.getGlobalLife().add( databaseManager );
        }
        platform.getGlobalDependencies().satisfyDependency( databaseManager );
        return databaseManager;
    }
}