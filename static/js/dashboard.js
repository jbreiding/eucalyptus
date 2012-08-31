/*************************************************************************
 * Copyright 2009-2012 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 ************************************************************************/

(function($, eucalyptus) {
  $.widget('eucalyptus.dashboard', $.eucalyptus.eucawidget, {
    options : { },
    _init : function() {
      var $tmpl = $('html body div.templates').find('#dashboardTmpl').clone();       
      var $div = $($tmpl.render($.i18n.map));
      this._setInstSummary($div.find('#dashboard-content .instances'));
      this._setStorageSummary($div.find('#dashboard-content .storage'));
      this._setNetSecSummary($div.find('#dashboard-content .netsec')); 
      $div.appendTo(this.element);
    },

    _create : function() { 
    },

    _destroy : function() { },
    
    // initiate ajax call-- describe-instances
    // attach spinning wheel until we refresh the content with the ajax response
    _setInstSummary : function($instObj) {
      var thisObj = this;
      var $az=$instObj.find('#dashboard-instance-az select');

      $('html body').eucadata('addCallback', 'zone', 'dashboard-summary', function(){
         var results = describe('zone');
         for( res in results) {
              var azName = results[res].name;
              $az.append($('<option>').attr('value', azName).text(azName));
         }
         $('html body').eucadata('removeCallback','zone','dashboard-summary');
      });
      $('html body').eucadata('refresh', 'zone');

            // update the display
      $az.change( function (e) {
        thisObj._reloadInstSummary($instObj);
      }); 

      // TODO: this is probably not the right place to call describe-instances. instances page should receive the data from server
      // selector is different for these two because of extra div
      $instObj.find('#dashboard-instance-running div').prepend(
        $('<img>').attr('src','images/dots32.gif'));
      $instObj.find('#dashboard-instance-stopped div').prepend(
        $('<img>').attr('src','images/dots32.gif'));
      thisObj._reloadInstSummary($instObj);
    },

    _reloadInstSummary : function($instObj){
      var thisObj = this;
      $('html body').eucadata('addCallback', 'instance', 'dashboard-summary', function(){
            // selector is different for these two because of extra div
        $instObj.find('#dashboard-instance-running div img').remove();
        $instObj.find('#dashboard-instance-stopped div img').remove();
        var numRunning = 0;
        var numStopped = 0;
        var az=$instObj.find('#dashboard-instance-az select').val();
        var results = describe('instance');
        $.each(results, function (idx, instance){
           // TODO: check if placement is the right identifier of availability zones
          if (az==='all' || instance.placement === az ){
            if (instance.state === 'running')
              numRunning++;
            else if (instance.state === 'stopped')
              numStopped++;
          }
        });
        $instObj.find('#dashboard-instance-running span').text(numRunning);
        $instObj.find('#dashboard-instance-stopped span').text(numStopped);
      }); 
      $('html body').eucadata('refresh','instance');

      $instObj.find('#dashboard-instance-running').wrapAll(
        $('<a>').attr('href','#').click( function(evt){
          thisObj._trigger('select', evt, {selected:'instance'});
        }));
      $instObj.find('#dashboard-instance-stopped').wrapAll(
        $('<a>').attr('href','#').click( function(evt){
            thisObj._trigger('select', evt, {selected:'instance'});
      }));
    },

    _setStorageSummary : function($storageObj) {
      var thisObj = this;

      $('html body').eucadata('addCallback', 'volume', 'dashboard-summary', function(){
        var results = describe('volume');
        var numVol = results ? results.length : 0;
        $storageObj.find('#dashboard-storage-volume img').remove();
        $storageObj.find('#dashboard-storage-volume span').text(numVol);
      });
      $('html body').eucadata('refresh', 'volume');
 
      $storageObj.find('#dashboard-storage-volume').wrapAll(
        $('<a>').attr('href','#').click( function(evt){
          thisObj._trigger('select', evt, {selected:'volume'});
      }));

      $('html body').eucadata('addCallback', 'snapshot', 'dashboard-summary', function(){
        var results = describe('snapshot');
        var numSnapshots = results ? results.length : 0;
        $storageObj.find('#dashboard-storage-snapshot img').remove();
        $storageObj.find('#dashboard-storage-snapshot span').text(numSnapshots);
      }); 
      $('html body').eucadata('refresh', 'snapshot');

      $storageObj.find('#dashboard-storage-snapshot').wrapAll(
        $('<a>').attr('href','#').click( function(evt){
          thisObj._trigger('select', evt, {selected:'snapshot'});
      }));

      //az = $instObj.find('#dashboard-instance-dropbox').value();
      $storageObj.find('#dashboard-storage-volume').prepend(
        $('<img>').attr('src','images/dots32.gif'));
      $storageObj.find('#dashboard-storage-snapshot').prepend(
        $('<img>').attr('src','images/dots32.gif'));
      $storageObj.find('#dashboard-storage-buckets').prepend(
        $('<img>').attr('src','images/dots32.gif'));
    },
  
    _setNetSecSummary : function($netsecObj) {
      var thisObj = this;
      $('html body').eucadata('addCallback', 'sgroup', 'dashboard-summary', function(){
        var results = describe('sgroup');
        var numGroups = results ? results.length : 0;
        $netsecObj.find('#dashboard-netsec-sgroup img').remove();
        $netsecObj.find('#dashboard-netsec-sgroup span').text(numGroups);
      });
      $netsecObj.find('#dashboard-netsec-sgroup').wrapAll(
        $('<a>').attr('href','#').click( function(evt){
          thisObj._trigger('select', evt, {selected:'sgroup'});
      }));
      $('html body').eucadata('refresh', 'sgroup'); 

      $('html body').eucadata('addCallback', 'eip', 'dashboard-summary', function(){
        var results = describe('eip');
        var numAddr = results ? results.length : 0;
        $netsecObj.find('#dashboard-netsec-eip img').remove();
        $netsecObj.find('#dashboard-netsec-eip span').text(numAddr);
      });
      $netsecObj.find('#dashboard-netsec-eip').wrapAll(
        $('<a>').attr('href','#').click( function(evt){
          thisObj._trigger('select', evt, {selected:'eip'});
      }));
      $('html body').eucadata('refresh', 'eip');

      $('html body').eucadata('addCallback', 'keypair', 'dashboard-summary', function(){
        var results = describe('keypair');
        var numKeypair = results ? results.length : 0;
        $netsecObj.find('#dashboard-netsec-keypair img').remove();
        $netsecObj.find('#dashboard-netsec-keypair span').text(numKeypair);
      });
      $netsecObj.find('#dashboard-netsec-keypair').wrapAll(
        $('<a>').attr('href','#').click( function(evt){
          thisObj._trigger('select', evt, {selected:'keypair'});
      }));
      $('html body').eucadata('refresh', 'keypair');

      //az = $instObj.find('#dashboard-instance-dropbox').value();
      $netsecObj.find('#dashboard-netsec-sgroup').prepend(
        $('<img>').attr('src','images/dots32.gif'));
      $netsecObj.find('#dashboard-netsec-eip').prepend(
        $('<img>').attr('src','images/dots32.gif'));
      $netsecObj.find('#dashboard-netsec-keypair').prepend(
        $('<img>').attr('src','images/dots32.gif'));
    },

    close: function() {
      $('html body').eucadata('removeCallback', 'instance', 'dashboard-summary');
      $('html body').eucadata('removeCallback', 'volume', 'dashboard-summary');
      $('html body').eucadata('removeCallback', 'snapshot', 'dashboard-summary');
      $('html body').eucadata('removeCallback', 'sgroup', 'dashboard-summary');
      $('html body').eucadata('removeCallback', 'eip', 'dashboard-summary');
      $('html body').eucadata('removeCallback', 'keypair', 'dashboard-summary');
      $('html body').eucadata('removeCallback','zone','dashboard-summary');
      this._super('close');
    }
  });
})(jQuery,
   window.eucalyptus ? window.eucalyptus : window.eucalyptus = {});
