package com.eucalyptus.reporting.art.generator

import static org.junit.Assert.*
import org.junit.Test
import com.eucalyptus.reporting.event_store.ReportingInstanceCreateEvent
import com.eucalyptus.reporting.event_store.ReportingInstanceUsageEvent
import com.eucalyptus.reporting.domain.ReportingUser
import com.eucalyptus.reporting.domain.ReportingAccount
import java.text.SimpleDateFormat
import com.google.common.base.Charsets
import com.google.common.collect.Sets
import com.eucalyptus.reporting.art.entity.AccountArtEntity
import com.eucalyptus.reporting.art.entity.ReportArtEntity
import com.eucalyptus.reporting.art.entity.UserArtEntity
import java.util.concurrent.TimeUnit
import com.eucalyptus.reporting.units.SizeUnit
import com.eucalyptus.reporting.art.entity.InstanceArtEntity
import com.eucalyptus.reporting.art.entity.InstanceUsageArtEntity
import com.eucalyptus.reporting.art.entity.UsageTotalsArtEntity

/**
 * 
 */
class InstanceArtGeneratorTest {
  private static String ACCOUNT1 = "account1"
  private static String USER1 = "user1"
  private static String USER2 = "user2"
  private static String INSTANCE1 = "i-00000001"
  private static String INSTANCE2 = "i-00000002"
  private static String ZONE1 = "zone1"
  private static String ZONE2 = "zone2"
  private static String VMTYPE1 = "vmtype1"
  private static String VMTYPE2 = "vmtype2"
  private static Map<String,String> metricToDimension = [
      "NetworkIn": "total",
      "NetworkOut": "total",
      "CPUUtilization": "default",
  ]
  private static Map<String,String> userToAccount = [
      (USER1): ACCOUNT1,
      (USER2): ACCOUNT1,
  ]

  @Test
  void testGenerationNoDataInPeriod(){
    InstanceArtGenerator generator = testGeneratorWith( usageBeforeReportPeriod() )
    ReportArtEntity art = generator.generateReportArt( new ReportArtEntity( millis("2012-08-25T00:00:00"), millis("2012-08-30T00:00:00") ) )
    assertEquals( "Accounts", Collections.emptySet(), art.getAccounts().keySet() )
    assertEquals( "Zones", Collections.emptySet(), art.getZones().keySet() )
  }

  @Test
  void testBasicGeneration(){
    InstanceArtGenerator generator = testGeneratorWith( basicUsageInReportPeriod() )

    ReportArtEntity art = generator.generateReportArt( new ReportArtEntity( millis("2012-09-01T00:00:00"), millis("2012-09-01T12:00:00") ) )

    assertArt( art )
  }

  @Test
  void testBasicGenerationMultipleZones(){
    InstanceArtGenerator generator = testGeneratorWith( basicUsageInReportPeriodMultipleZones() )

    ReportArtEntity art = generator.generateReportArt( new ReportArtEntity( millis("2012-09-01T00:00:00"), millis("2012-09-01T12:00:00") ) )

    assertArt( art, 1, [ ZONE1, ZONE2 ] )
  }

  @Test
  void testBasicGenerationMultipleDisks(){
    InstanceArtGenerator generator = testGeneratorWith( basicUsageInReportPeriodWithMultipleDisks() )

    ReportArtEntity art = generator.generateReportArt( new ReportArtEntity( millis("2012-09-01T00:00:00"), millis("2012-09-01T12:00:00") ) )

    assertArt( art, 3 )
  }

  @Test
  void testInterpolatedUsageExitingReportPeriod(){
    InstanceArtGenerator generator = testGeneratorWith( interpolatedUsageWithDates( "2012-09-01T00:00:00", "2012-09-02T00:00:00", 2 ) )

    ReportArtEntity art = generator.generateReportArt( new ReportArtEntity( millis("2012-09-01T00:00:00"), millis("2012-09-01T12:00:00") ) )

    assertArt( art )
  }

  @Test
  void testInterpolatedUsageEnteringReportPeriod(){
    InstanceArtGenerator generator = testGeneratorWith( interpolatedUsageWithDates( "2012-08-31T12:00:00", "2012-09-01T12:00:00", 2 ) )

    ReportArtEntity art = generator.generateReportArt( new ReportArtEntity( millis("2012-09-01T00:00:00"), millis("2012-09-01T12:00:00") ) )

    assertArt( art )
  }

  @Test
  void testInterpolatedUsageReportPeriod(){
    InstanceArtGenerator generator = testGeneratorWith( interpolatedUsageWithDates( "2012-08-31T12:00:00", "2012-09-02T00:00:00", 3 ) )

    ReportArtEntity art = generator.generateReportArt( new ReportArtEntity( millis("2012-09-01T00:00:00"), millis("2012-09-01T12:00:00") ) )

    assertArt( art )
  }

  @Test
  void testSequenceReset() {
    InstanceArtGenerator generator = testGeneratorWith( sequenceResetUsage() )

    ReportArtEntity art = generator.generateReportArt( new ReportArtEntity( millis("2012-09-01T00:00:00"), millis("2012-09-01T12:00:00") ) )

    assertArt( art, 2 )
  }

  @SuppressWarnings("GroovyAccessibility")
  private ReportingInstanceCreateEvent instanceCreate(
      String instanceId,
      String userId,
      String timestamp,
      String vmType,
      String zone
  ) {
    new ReportingInstanceCreateEvent( uuid(instanceId), instanceId, millis(timestamp), vmType, userId, zone )
  }

  @SuppressWarnings("GroovyAccessibility")
  private ReportingInstanceUsageEvent instanceUsage(
      String instanceId,
      String metric,
      Integer sequenceNum,
      String dimension,
      Double value,
      String timestamp ) {
    new ReportingInstanceUsageEvent( uuid(instanceId), metric, sequenceNum, dimension, value, millis(timestamp) )
  }

  @SuppressWarnings("GroovyAccessibility")
  private ReportingUser user( String id, String accountId ) {
    new ReportingUser( id, accountId, name(id) )
  }

  @SuppressWarnings("GroovyAccessibility")
  private ReportingAccount account( String id ) {
    new ReportingAccount( id, name(id) )
  }

  private String name( String id ) {
    id + "-name"
  }

  private long millis( String timestamp ) {
    final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    sdf.parse( timestamp ).getTime()
  }

  private String uuid( String seed ) {
    UUID.nameUUIDFromBytes( seed.getBytes(Charsets.UTF_8) ).toString();
  }

  private Double mbd( int mibibytes ) {
    mb(mibibytes)
  }

  private long mb( int mibibytes ) {
    mibibytes * SizeUnit.MB.factor
  }

  private Double msd( int hours ) {
    ms(hours)
  }

  private long ms( int hours ) {
    TimeUnit.HOURS.toMillis( hours )
  }

  private void assertArt( ReportArtEntity art, int diskUsageMultiplier=1, List<String> zones=[ZONE1] ) {
    assertEquals("Accounts", Collections.emptySet(), art.getAccounts().keySet())
    assertEquals("Zones", Sets.newHashSet(zones), art.getZones().keySet())

    zones.each{ zone ->
      UsageTotalsArtEntity zoneUsageTotals = art.getZones().get(zone).getUsageTotals()
      InstanceUsageArtEntity zoneUsage = zoneUsageTotals.getInstanceTotals()
      assertEquals( zone + " total usage instances", 1, zoneUsage.getInstanceCnt() )
      assertUsage( zone + " total", zoneUsage, diskUsageMultiplier )

      Map<String,AccountArtEntity> accounts = art.getZones().get(zone).getAccounts()
      assertEquals( zone + " accounts", Sets.newHashSet(name(ACCOUNT1)), accounts.keySet() )
      InstanceUsageArtEntity account1Usage = accounts.get(name(ACCOUNT1)).getUsageTotals().getInstanceTotals()
      assertEquals( "Account1 total usage instances", 1, account1Usage.getInstanceCnt() )
      assertUsage( "Account1 total", account1Usage, diskUsageMultiplier )

      if ( ZONE1.equals( zone ) ) {
        assertUser( accounts, diskUsageMultiplier, zone, USER1, INSTANCE1, VMTYPE1 )
        assertVmTypeTotals( zone, diskUsageMultiplier, zoneUsageTotals.getTypeTotals(), VMTYPE1 )
        assertVmTypeTotals( zone + " " + ACCOUNT1, diskUsageMultiplier, accounts.get(name(ACCOUNT1)).getUsageTotals().getTypeTotals(), VMTYPE1 )
      } else if ( ZONE2.equals( zone ) ) {
        assertUser( accounts, diskUsageMultiplier, zone, USER2, INSTANCE2, VMTYPE2 )
        assertVmTypeTotals( zone, diskUsageMultiplier, zoneUsageTotals.getTypeTotals(), VMTYPE2 )
        assertVmTypeTotals( zone + " " + ACCOUNT1, diskUsageMultiplier, accounts.get(name(ACCOUNT1)).getUsageTotals().getTypeTotals(), VMTYPE2 )
      }
    }
  }

  void assertVmTypeTotals( String description,
                           int diskUsageMultiplier,
                           Map<String, InstanceUsageArtEntity> typeToUsage,
                           String vmType ) {
    assertNotNull( description + " " + vmType, typeToUsage.get(vmType) )
    assertUsage( description + " " + vmType + " total", typeToUsage.get(vmType), diskUsageMultiplier )
  }

  private void assertUser( Map<String, AccountArtEntity> accounts, int diskUsageMultiplier, String zone, String user, String instance, String vmType ) {
    UserArtEntity userArtEntity = accounts.get(name(ACCOUNT1)).users.get(name(user))
    assertEquals(zone + "account1 " +user+ " instances", Sets.newHashSet(uuid(instance)), userArtEntity.getInstances().keySet())
    InstanceUsageArtEntity userUsage = userArtEntity.getUsageTotals().getInstanceTotals();
    assertEquals( user + " total usage instances", 1, userUsage.getInstanceCnt())
    assertUsage( user + " total", userUsage, diskUsageMultiplier)
    assertVmTypeTotals( zone + " " + ACCOUNT1 + " " + user, diskUsageMultiplier, userArtEntity.getUsageTotals().getTypeTotals(), vmType )

    InstanceArtEntity instance1 = userArtEntity.getInstances().get(uuid(instance))
    assertEquals(instance + " id", instance, instance1.getInstanceId())
    assertEquals(instance + " type", vmType, instance1.getInstanceType())
    assertUsage(instance, instance1.getUsage(), diskUsageMultiplier)
  }

  private void assertUsage( String description, InstanceUsageArtEntity usage, int diskUsageMultiplier=1 ) {
    assertEquals( description + " duration", ms(12), usage.getDurationMs() );
    assertEquals( description + " usage net in", 100, usage.getNetTotalInMegs() )
    assertEquals( description + " usage net out", 200, usage.getNetTotalOutMegs() )
    assertEquals( description + " usage cpu ms", ms(6), usage.getCpuUtilizationMs() )
    assertEquals( description + " usage disk read ops", diskUsageMultiplier * 50000, usage.getDiskReadOps() )
    assertEquals( description + " usage disk write ops", diskUsageMultiplier * 20000, usage.getDiskWriteOps() )
    assertEquals( description + " usage disk read size", diskUsageMultiplier * 2000, usage.getDiskReadMegs() )
    assertEquals( description + " usage disk write size", diskUsageMultiplier * 1000, usage.getDiskWriteMegs() )
    assertEquals( description + " usage disk read time", diskUsageMultiplier * 8000, usage.getDiskReadTime() )
    assertEquals( description + " usage disk write time", diskUsageMultiplier * 4000, usage.getDiskWriteTime() )
  }

  private List<ReportingInstanceUsageEvent> usageBeforeReportPeriod() {
    List<ReportingInstanceUsageEvent> instanceUsageList = []
    addUsage( instanceUsageList, INSTANCE1, "2012-08-01T01:00:01", 0, [
      "NetworkIn": 0,
      "NetworkOut": 0,
      "CPUUtilization": 0,
    ], [ "vda": [
        "DiskReadOps": 0,
        "DiskWriteOps": 0,
        "DiskReadBytes": 0,
        "DiskWriteBytes": 0,
        "VolumeTotalReadTime": 0,
        "VolumeTotalWriteTime": 0,
    ] ] )
    instanceUsageList
  }

  private List<ReportingInstanceUsageEvent> basicUsageInReportPeriod() {
    List<ReportingInstanceUsageEvent> instanceUsageList = []
    addUsage( instanceUsageList, INSTANCE1, "2012-09-01T00:00:00", 1, [
      "NetworkIn": 0,
      "NetworkOut": 0,
      "CPUUtilization": 0,
    ], [ "vda": [
        "DiskReadOps": 0,
        "DiskWriteOps": 0,
        "DiskReadBytes": 0,
        "DiskWriteBytes": 0,
        "VolumeTotalReadTime": 0,
        "VolumeTotalWriteTime": 0,
    ] ] )
    addUsage( instanceUsageList, INSTANCE1, "2012-09-01T12:00:00", 2, [
      "NetworkIn": mbd(100),
      "NetworkOut": mbd(200),
      "CPUUtilization": msd(6),
    ], [ "vda": [
        "DiskReadOps": 50000,
        "DiskWriteOps": 20000,
        "DiskReadBytes": mbd(2000),
        "DiskWriteBytes": mbd(1000),
        "VolumeTotalReadTime": 8000,
        "VolumeTotalWriteTime": 4000,
    ] ] )
    instanceUsageList
  }

  private List<ReportingInstanceUsageEvent> basicUsageInReportPeriodMultipleZones() {
    List<ReportingInstanceUsageEvent> instanceUsageList = []
    [ INSTANCE1, INSTANCE2 ].each{ instance ->
      addUsage( instanceUsageList, instance, "2012-09-01T00:00:00", 1, [
          "NetworkIn": 0,
          "NetworkOut": 0,
          "CPUUtilization": 0,
      ], [ "vda": [
          "DiskReadOps": 0,
          "DiskWriteOps": 0,
          "DiskReadBytes": 0,
          "DiskWriteBytes": 0,
          "VolumeTotalReadTime": 0,
          "VolumeTotalWriteTime": 0,
      ] ] )
      addUsage( instanceUsageList, instance, "2012-09-01T12:00:00", 2, [
          "NetworkIn": mbd(100),
          "NetworkOut": mbd(200),
          "CPUUtilization": msd(6),
      ], [ "vda": [
          "DiskReadOps": 50000,
          "DiskWriteOps": 20000,
          "DiskReadBytes": mbd(2000),
          "DiskWriteBytes": mbd(1000),
          "VolumeTotalReadTime": 8000,
          "VolumeTotalWriteTime": 4000,
      ] ] )
    }
    instanceUsageList
  }

  private List<ReportingInstanceUsageEvent> basicUsageInReportPeriodWithMultipleDisks() {
    List<ReportingInstanceUsageEvent> instanceUsageList = []
    addUsage( instanceUsageList, INSTANCE1, "2012-09-01T00:00:00", 1, [
        "NetworkIn": 0,
        "NetworkOut": 0,
        "CPUUtilization": 0,
    ], [ "vda": [
        "DiskReadOps": 0,
        "DiskWriteOps": 0,
        "DiskReadBytes": 0,
        "DiskWriteBytes": 0,
        "VolumeTotalReadTime": 0,
        "VolumeTotalWriteTime": 0,
    ], "vdb": [
        "DiskReadOps": 0,
        "DiskWriteOps": 0,
        "DiskReadBytes": 0,
        "DiskWriteBytes": 0,
        "VolumeTotalReadTime": 0,
        "VolumeTotalWriteTime": 0,
    ] ] )
    addUsage( instanceUsageList, INSTANCE1, "2012-09-01T12:00:00", 2, [
        "NetworkIn": mbd(100),
        "NetworkOut": mbd(200),
        "CPUUtilization": msd(6),
    ], [ "vda": [
        "DiskReadOps": 50000,
        "DiskWriteOps": 20000,
        "DiskReadBytes": mbd(2000),
        "DiskWriteBytes": mbd(1000),
        "VolumeTotalReadTime": 8000,
        "VolumeTotalWriteTime": 4000,
    ], "vdb": [
        "DiskReadOps": 100000,
        "DiskWriteOps": 40000,
        "DiskReadBytes": mbd(4000),
        "DiskWriteBytes": mbd(2000),
        "VolumeTotalReadTime": 16000,
        "VolumeTotalWriteTime": 8000,
    ]  ] )
    instanceUsageList
  }

  private List<ReportingInstanceUsageEvent> interpolatedUsageWithDates( String usage1Timestamp,
                                                                        String usage2Timestamp,
                                                                        int multiplier ) {
    List<ReportingInstanceUsageEvent> instanceUsageList = []
    addUsage( instanceUsageList, INSTANCE1, usage1Timestamp, 1, [
      "NetworkIn": 0,
      "NetworkOut": 0,
      "CPUUtilization": 0,
    ], [ "vda": [
        "DiskReadOps": 0,
        "DiskWriteOps": 0,
        "DiskReadBytes": 0,
        "DiskWriteBytes": 0,
        "VolumeTotalReadTime": 0,
        "VolumeTotalWriteTime": 0,
    ] ] )
    addUsage( instanceUsageList, INSTANCE1, usage2Timestamp, 2, [
      "NetworkIn": mbd(multiplier * 100),
      "NetworkOut": mbd(multiplier * 200),
      "CPUUtilization": msd(multiplier * 6),
    ], [ "vda": [
        "DiskReadOps": multiplier * 50000,
        "DiskWriteOps": multiplier * 20000,
        "DiskReadBytes": mbd(multiplier * 2000),
        "DiskWriteBytes": mbd(multiplier * 1000),
        "VolumeTotalReadTime": multiplier * 8000,
        "VolumeTotalWriteTime": multiplier * 4000,
    ] ] )
    instanceUsageList
  }

  private List<ReportingInstanceUsageEvent> sequenceResetUsage() {
    List<ReportingInstanceUsageEvent> instanceUsageList = []
    addUsage( instanceUsageList, INSTANCE1, "2012-09-01T00:00:00", 100, [
      "NetworkIn": 0,
      "NetworkOut": 0,
      "CPUUtilization": 0,
    ], [ "vda": [
        "DiskReadOps": 0,
        "DiskWriteOps": 0,
        "DiskReadBytes": 0,
        "DiskWriteBytes": 0,
        "VolumeTotalReadTime": 0,
        "VolumeTotalWriteTime": 0,
    ] ] )
    addUsage( instanceUsageList, INSTANCE1, "2012-09-01T06:00:00", 101, [
      "NetworkIn": mbd(100),
      "NetworkOut": mbd(200),
      "CPUUtilization": msd(6),
    ], [ "vda": [
        "DiskReadOps": 50000,
        "DiskWriteOps": 20000,
        "DiskReadBytes": mbd(2000),
        "DiskWriteBytes": mbd(1000),
        "VolumeTotalReadTime": 8000,
        "VolumeTotalWriteTime": 4000,
    ] ] )
    addUsage( instanceUsageList, INSTANCE1, "2012-09-01T12:00:00", 0, [
      "NetworkIn": mbd(100),
      "NetworkOut": mbd(200),
      "CPUUtilization": msd(6),
    ], [ "vda": [
        "DiskReadOps": 50000,
        "DiskWriteOps": 20000,
        "DiskReadBytes": mbd(2000),
        "DiskWriteBytes": mbd(1000),
        "VolumeTotalReadTime": 8000,
        "VolumeTotalWriteTime": 4000,
    ] ] )
    instanceUsageList
  }

  private InstanceArtGenerator testGeneratorWith( List<ReportingInstanceUsageEvent> usage ) {
    List<ReportingInstanceCreateEvent> instanceCreateList = [
        instanceCreate( INSTANCE1, USER1, "2012-08-01T01:00:00", VMTYPE1, ZONE1 ),
        instanceCreate( INSTANCE2, USER2, "2012-09-01T01:00:00", VMTYPE2, ZONE2 ),
    ]
    List<ReportingInstanceUsageEvent> instanceUsageList = usage.sort{ event -> event.getTimestampMs() }
    new InstanceArtGenerator() {
      @Override
      protected Iterator<ReportingInstanceCreateEvent> getInstanceCreateEventIterator() {
        return instanceCreateList.iterator()
      }

      @Override
      protected Iterator<ReportingInstanceUsageEvent> getInstanceUsageEventIterator() {
        return instanceUsageList.iterator();
      }

      @Override
      protected ReportingUser getUserById(String userId) {
        return user( userId, userToAccount[userId] )
      }

      @Override
      protected ReportingAccount getAccountById(String accountId) {
        return account( accountId )
      }
    }
  }

  private void addUsage( List<ReportingInstanceUsageEvent> instanceUsageList,
                         String instanceId,
                         String timestamp,
                         int sequence,
                         Map<String,Double> metrics,
                         Map<String,Map<String,Double>> diskMetrics ) {
    metrics.each { metric, value ->
      instanceUsageList  \
         << instanceUsage( instanceId, metric, sequence, metricToDimension[metric], value, timestamp )
    }

    diskMetrics.each { disk, metricMap ->
      metricMap.each { metric, value ->
        instanceUsageList  \
         << instanceUsage( instanceId, metric, sequence, disk, value, timestamp )
      }
    }
  }

  private void dumpArt( ReportArtEntity art ) {
    dumpObject( "art", art )
  }

  private void dumpObject( String prefix, Object object ) {
    if ( object instanceof Map ) {
      ((Map)object).each { name, value ->
        dumpNVP( prefix, name, value )
      }
    } else {
      object.properties.each { name, value ->
        if ( "class".equals( name ) ) return
        dumpNVP( prefix, name, value )
      }
    }
  }

  private void dumpNVP( String prefix, Object name, Object value ) {
    if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
      println prefix + "." + name + " = " + value
    } else {
      dumpObject(prefix + "." + name, value)
    }
  }
}
