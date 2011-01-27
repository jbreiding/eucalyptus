package com.eucalyptus.context;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.log4j.Logger;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.exception.MethodInvocationException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.mule.DefaultMuleEvent;
import org.mule.DefaultMuleMessage;
import org.mule.DefaultMuleSession;
import org.mule.RequestContext;
import org.mule.api.MuleContext;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.MuleSession;
import org.mule.api.context.MuleContextFactory;
import org.mule.api.endpoint.InboundEndpoint;
import org.mule.api.endpoint.OutboundEndpoint;
import org.mule.api.registry.Registry;
import org.mule.api.service.Service;
import org.mule.config.ConfigResource;
import org.mule.config.spring.SpringXmlConfigurationBuilder;
import org.mule.context.DefaultMuleContextFactory;
import org.mule.module.client.MuleClient;
import org.mule.transport.AbstractConnector;
import org.mule.transport.vm.VMMessageDispatcherFactory;
import com.eucalyptus.bootstrap.BootstrapException;
import com.eucalyptus.component.Component;
import com.eucalyptus.component.ComponentId;
import com.eucalyptus.component.ComponentIds;
import com.eucalyptus.component.Components;
import com.eucalyptus.component.Resource;
import com.eucalyptus.component.id.Eucalyptus;
import com.eucalyptus.configurable.ConfigurableClass;
import com.eucalyptus.configurable.ConfigurableField;
import com.eucalyptus.configurable.ConfigurableProperty;
import com.eucalyptus.configurable.ConfigurablePropertyException;
import com.eucalyptus.configurable.PropertyChangeListener;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.Exceptions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

@ConfigurableClass( root = "system", description = "Parameters having to do with the system's state.  Mostly read-only." )
public class ServiceContext {
  private static Logger                        LOG                      = Logger.getLogger( ServiceContext.class );
  private static SpringXmlConfigurationBuilder builder;
  @ConfigurableField( initial = "16", description = "Max queue length allowed per service stage.", changeListener = HupListener.class )
  public static Integer                        MAX_OUTSTANDING_MESSAGES = 16;
  @ConfigurableField( initial = "0", description = "Do a soft reset.", changeListener = HupListener.class )
  public static Integer                        HUP                      = 0;
  static {
    Velocity.init( );
  }
  public static class HupListener implements PropertyChangeListener {
    @Override
    public void fireChange( ConfigurableProperty t, Object newValue ) throws ConfigurablePropertyException {
      if ( "123".equals( t.getValue( ) ) ) {
        System.exit( 123 );
      }
    }
  }
  
  private static AtomicReference<MuleContext>           context           = new AtomicReference<MuleContext>( null );
  private static AtomicBoolean                          ready             = new AtomicBoolean( false );
  private static ConcurrentNavigableMap<String, String> endpointToService = new ConcurrentSkipListMap<String, String>( );
  private static ConcurrentNavigableMap<String, String> serviceToEndpoint = new ConcurrentSkipListMap<String, String>( );
  private static VMMessageDispatcherFactory             dispatcherFactory = new VMMessageDispatcherFactory( );
  private static AtomicReference<MuleClient>            client            = new AtomicReference<MuleClient>( null );
  private static final BootstrapException               failEx            = new BootstrapException(
                                                                                                    "Attempt to use esb client before the service bus has been started." );
  
  private static MuleClient getClient( ) throws MuleException {
    if ( context.get( ) == null ) {
      LOG.fatal( failEx, failEx );
      System.exit( 123 );
      throw failEx;
    } else if ( client.get( ) == null && client.compareAndSet( null, new MuleClient( context.get( ) ) ) ) {
      return client.get( );
    } else {
      return client.get( );
    }
  }
  
  public static void dispatch( String dest, Object msg ) throws EucalyptusCloudException {
    if ( ( !dest.startsWith( "vm://" ) && !serviceToEndpoint.containsKey( dest ) ) || dest == null ) {
      dest = "vm://RequestQueue";
    } else if ( !dest.startsWith( "vm://" ) ) {
      dest = serviceToEndpoint.get( dest );
    }
    try {
      OutboundEndpoint endpoint = ServiceContext.getContext( ).getRegistry( ).lookupEndpointFactory( ).getOutboundEndpoint( dest );
      if ( !endpoint.getConnector( ).isStarted( ) ) {
        endpoint.getConnector( ).start( );
      }
      MuleMessage muleMsg = new DefaultMuleMessage( msg );
      MuleSession muleSession = new DefaultMuleSession( muleMsg, ( ( AbstractConnector ) endpoint.getConnector( ) ).getSessionHandler( ),
                                                        ServiceContext.getContext( ) );
      MuleEvent muleEvent = new DefaultMuleEvent( muleMsg, endpoint, muleSession, false );
      LOG.debug( "ServiceContext.dispatch(" + dest + ":" + msg.getClass( ).getCanonicalName( ), Exceptions.filterStackTrace( new RuntimeException( ), 3 ) );
      dispatcherFactory.create( endpoint ).dispatch( muleEvent );
    } catch ( Exception ex ) {
      LOG.error( ex, ex );
      throw new EucalyptusCloudException( "Failed to dispatch request to service " + dest + " for message type: " + msg.getClass( ).getSimpleName( )
                                          + " because of an error: " + ex.getMessage( ), ex );
    }
  }
  
  public static <T> T send( String dest, Object msg ) throws EucalyptusCloudException {
    if ( ( dest.startsWith( "vm://" ) && !endpointToService.containsKey( dest ) ) || dest == null ) {
      throw new EucalyptusCloudException( "Failed to find destination: " + dest, new IllegalArgumentException( "No such endpoint: " + dest + " in endpoints="
                                                                                                               + endpointToService.entrySet( ) ) );
    }
    if ( dest.startsWith( "vm://" ) ) {
      dest = endpointToService.get( dest );
    }
    MuleEvent context = RequestContext.getEvent( );
    try {
      LOG.debug( "ServiceContext.send(" + dest + ":" + msg.getClass( ).getCanonicalName( ), Exceptions.filterStackTrace( new RuntimeException( ), 3 ) );
      MuleMessage reply = ServiceContext.getClient( ).sendDirect( dest, null, new DefaultMuleMessage( msg ) );
      
      if ( reply.getExceptionPayload( ) != null ) {
        EucalyptusCloudException ex = new EucalyptusCloudException( reply.getExceptionPayload( ).getRootException( ).getMessage( ),
                                                                    reply.getExceptionPayload( ).getRootException( ) );
        LOG.trace( ex, ex );
        throw ex;
      } else return ( T ) reply.getPayload( );
    } catch ( Throwable e ) {
      EucalyptusCloudException ex = new EucalyptusCloudException( "Failed to send message " + msg.getClass( ).getSimpleName( ) + " to service " + dest
                                                                  + " because of " + e.getMessage( ), e );
      LOG.trace( ex, ex );
      throw ex;
    } finally {
      RequestContext.setEvent( context );
    }
  }
  
  public static void createContext( ) {
    MuleContextFactory contextFactory = new DefaultMuleContextFactory( );
    try {
      MuleContext context = contextFactory.createMuleContext( builder );
      if ( !ServiceContext.context.compareAndSet( null, context ) ) {
        throw new ServiceInitializationException( "Service context initialized twice." );
      }
    } catch ( Exception e ) {
      LOG.error( e, e );
      throw new ServiceInitializationException( "Failed to build service context.", e );
    }
  }
  
  public static void startContext( ) {
    try {
      if ( !ServiceContext.getContext( ).isInitialised( ) ) {
        ServiceContext.getContext( ).initialise( );
      }
    } catch ( Throwable e ) {
      LOG.error( e, e );
      throw new ServiceInitializationException( "Failed to initialize service context.", e );
    }
    try {
      ServiceContext.getContext( ).start( );
      endpointToService.clear( );
      serviceToEndpoint.clear( );
      for ( Object o : ServiceContext.getContext( ).getRegistry( ).lookupServices( ) ) {
        Service s = ( Service ) o;
        for ( Object p : s.getInboundRouter( ).getEndpoints( ) ) {
          InboundEndpoint in = ( InboundEndpoint ) p;
          endpointToService.put( in.getEndpointURI( ).toString( ), s.getName( ) );
          serviceToEndpoint.put( s.getName( ), in.getEndpointURI( ).toString( ) );
        }
      }
    } catch ( Throwable e ) {
      LOG.error( e, e );
      throw new ServiceInitializationException( "Failed to start service context.", e );
    }
  }
  
  public static MuleContext getContext( ) {
    if ( ServiceContext.context.get( ) == null ) {
      throw new ServiceInitializationException( "Attempt to reference service context before it is ready." );
    } else {
      return context.get( );
    }
  }
  
  public static Registry getRegistry( ) {
    return ServiceContext.getContext( ).getRegistry( );
  }
  
  public static synchronized void shutdown( ) {
    try {
      ServiceContext.getContext( ).stop( );
      ServiceContext.getContext( ).dispose( );
      context.set( null );
    } catch ( Throwable e ) {
      LOG.debug( e, e );
    }
  }
  
  
  static boolean loadContext( ) {
    List<ComponentId> components = ComponentIds.listEnabled( );
    LOG.info( "The following components have been identified as active: " );
    for( ComponentId c : components ) {
      LOG.info( "-> " + c );
    }
    Set<ConfigResource> configs = ServiceContext.renderServiceConfigurations( components );
    for ( ConfigResource cfg : configs ) {
      LOG.info( "-> Rendered cfg: " + cfg.getResourceName( ) );
    }
    try {
      ServiceContext.builder = new SpringXmlConfigurationBuilder( configs.toArray( new ConfigResource[] {} ) );
    } catch ( Exception e ) {
      LOG.fatal( "Failed to bootstrap services.", e );
      return false;
    }
    return true;
  }

  private static Set<ConfigResource> renderServiceConfigurations( List<ComponentId> components ) {
    Set<ConfigResource> configs = Sets.newHashSet( );
    for( ComponentId thisComponent : components ) {
      VelocityContext context = new VelocityContext( );
      context.put( "components", components );
      context.put( "thisComponent", thisComponent );
      LOG.info( "-> Rendering configuration for " + thisComponent.name( ) );
      String templateName = thisComponent.getServiceModel( );
      StringWriter out = new StringWriter();
      try {
        Velocity.evaluate( context, out, thisComponent.getServiceModelFileName( ), thisComponent.getServiceModelAsReader( ) );
        ConfigResource configRsc = new ConfigResource( thisComponent.getServiceModelFileName( ), new ByteArrayInputStream( out.toString( ).getBytes( ) ) );
        configs.add( configRsc );
      } catch ( Throwable ex ) {
        LOG.error( "Failed to render service model configuration for: " + thisComponent + " because of: " + ex.getMessage( ), ex );
      }
    }
    return configs;
  }
  
  public static synchronized boolean startup( ) {
    try {
      LOG.info( "Loading system bus." );
      loadContext( );
    } catch ( Exception e ) {
      LOG.fatal( "Failed to configure services.", e );
      return false;
    }
    try {
      LOG.info( "Starting up system bus." );
      createContext( );
    } catch ( Exception e ) {
      LOG.fatal( "Failed to configure services.", e );
      return false;
    }
    try {
      startContext( );
    } catch ( Exception e ) {
      LOG.fatal( "Failed to start services.", e );
      return false;
    }
    return true;
  }
  
}
