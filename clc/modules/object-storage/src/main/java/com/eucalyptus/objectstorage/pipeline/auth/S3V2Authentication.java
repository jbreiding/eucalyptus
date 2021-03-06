/*************************************************************************
 * Copyright 2016 Hewlett-Packard Enterprise, Inc.
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

package com.eucalyptus.objectstorage.pipeline.auth;

import com.eucalyptus.auth.login.SecurityContext;
import com.eucalyptus.component.ComponentIds;
import com.eucalyptus.crypto.util.SecurityParameter;
import com.eucalyptus.http.MappingHttpRequest;
import com.eucalyptus.objectstorage.ObjectStorage;
import com.eucalyptus.objectstorage.exceptions.s3.*;
import com.eucalyptus.objectstorage.util.OSGUtil;
import com.eucalyptus.objectstorage.util.ObjectStorageProperties;
import com.google.common.base.Strings;

import javaslang.control.Try.CheckedFunction;

import org.apache.log4j.Logger;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;

import javax.security.auth.login.LoginException;

import java.util.*;

/**
 * S3 V2 specific authentication utilities.
 */
final class S3V2Authentication {
  private static final Logger LOG = Logger.getLogger(S3V4Authentication.class);
  static final String AWS_V2_AUTH_TYPE = "AWS";

  enum ExcludeFromSignature {
    NONE, PATH, CONTENT_TYPE;
  }

  private S3V2Authentication() {
  }

  static void login(MappingHttpRequest request, Date signatureDate, String date, String canonicalizedAmzHeaders,
                    String accessKeyId, String signature, String securityToken) throws S3Exception {
    login(request, accessKeyId, excludeOption -> {
      String stringToSign = buildStringToSign(request, date, canonicalizedAmzHeaders, excludeOption);
      return new ObjectStorageWrappedCredentials(request.getCorrelationId(),
          signatureDate==null?null:signatureDate.getTime(), stringToSign, accessKeyId, signature, securityToken);
    });
  }

  static ObjectStorageWrappedCredentials credentialsFor(CheckedFunction<ExcludeFromSignature, ObjectStorageWrappedCredentials> credsFn,
      ExcludeFromSignature exclude) throws S3Exception {
    try {
      return credsFn.apply(exclude);
    } catch (Throwable t) {
      if (t instanceof S3Exception)
        throw (S3Exception) t;
      throw new InternalErrorException(t);
    }
  }
  /**
   * Attempts a login and retries sign a signed string that does not contain a path or Content-Type if the initial attempt fails.
   */
  private static void login(MappingHttpRequest request, String accessKeyId,
      CheckedFunction<ExcludeFromSignature, ObjectStorageWrappedCredentials> credsFn) throws S3Exception {
    // Build credentials that includes path
    ObjectStorageWrappedCredentials creds = credentialsFor(credsFn, ExcludeFromSignature.NONE);

    try {
      SecurityContext.getLoginContext(creds).login();
    } catch (LoginException ex) {
      if (ex.getMessage().contains("The AWS Access Key Id you provided does not exist in our records"))
        throw new InvalidAccessKeyIdException(accessKeyId);

      if (request.getUri().startsWith(ComponentIds.lookup(ObjectStorage.class).getServicePath()) || request.getUri().startsWith
          (ObjectStorageProperties.LEGACY_WALRUS_SERVICE_PATH)) {
        try {
          LOG.debug("Fallback to login without resource path");
          // Build credentials for a string to sign that excludes the resource path
          creds = credentialsFor(credsFn, ExcludeFromSignature.PATH);
          SecurityContext.getLoginContext(creds).login();
        } catch (Exception ex2) {
          LOG.debug("CorrelationId: " + request.getCorrelationId() + " Authentication failed due to signature match issue:", ex2);
          throw new SignatureDoesNotMatchException(creds.accessKeyId, creds.getLoginData(), creds.signature);
        }
      } else if (request.getMethod() == HttpMethod.GET || request.getMethod() == HttpMethod.HEAD) {
        // Build credentials for a string to sign that excludes the Content-Type
        try {
          LOG.debug("Fallback to login without content-type");
          creds = credentialsFor(credsFn, ExcludeFromSignature.CONTENT_TYPE);
          SecurityContext.getLoginContext(creds).login();
        } catch (Exception ex2) {
          LOG.debug("CorrelationId: " + request.getCorrelationId() + " Authentication failed due to signature match issue:", ex2);
          throw new SignatureDoesNotMatchException(creds.accessKeyId, creds.getLoginData(), creds.signature);
        }
      } else {
        throw new SignatureDoesNotMatchException(creds.accessKeyId, creds.getLoginData(), creds.signature);
      }
    } catch (Exception e) {
      LOG.warn("CorrelationId: " + request.getCorrelationId() + " Unexpected failure trying to authenticate request", e);
      throw new InternalErrorException(e);
    }
  }

  /*
  * @param if exclude is ExcludeFromSignature.CONTENT_TYPE, removes the content type from the address string if found
  */
  private static String buildStringToSign(MappingHttpRequest request, String date, String canonicalizedAmzHeaders, ExcludeFromSignature exclude)
      throws S3Exception {
    String contentMd5 = request.getHeader(HttpHeaders.Names.CONTENT_MD5);
    contentMd5 = contentMd5 == null ? "" : contentMd5;
    String contentType = request.getHeader(HttpHeaders.Names.CONTENT_TYPE);
    contentType = contentType == null ? "" : contentType;
    String address = buildCanonicalResource(request, exclude);
    StringBuilder sb = new StringBuilder(request.getMethod().getName());
    sb.append("\n").append(contentMd5).append("\n");
    if (exclude != ExcludeFromSignature.CONTENT_TYPE)
      sb.append(contentType);
    sb.append("\n").append(date).append("\n").append(canonicalizedAmzHeaders).append(address);
    return sb.toString();
  }

  /**
   * AWS S3-spec address string, which includes the query parameters
   *
   * @param if exclude is ExcludeFromSignature.PATH, removes the service path from the address string if found and if the request is path-style
   * @see <a href="http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#ConstructingTheCanonicalizedResourceElement">AWS
   * Docs</a>
   */
  static String buildCanonicalResource(MappingHttpRequest httpRequest, ExcludeFromSignature exclude) throws S3Exception {
    /*
      There are two modes: dns-style and path-style. dns-style has the bucket name in the HOST header path-style has
      the bucket name in the request path.

      If using DNS-style, we assume the key is the path, no service path necessary or allowed If using path-style,
      there may be service path as well
      that prefixes the bucket name (e.g. /services/objectstorage/bucket/key)
     */
    try {
      String addr = httpRequest.getUri();
      String osgServicePath = ComponentIds.lookup(ObjectStorage.class).getServicePath();
      String key;

      StringBuilder addrString = new StringBuilder();

      // Normalize the URI
      boolean foundName = false;
      String hostBucket;
      if ((hostBucket = OSGUtil.getBucketFromHostHeader(httpRequest)) != null) {
        // dns-style request
        foundName = true;
        addrString.append("/").append(hostBucket);
      }

      if (!foundName) {
        // path-style request (or service request that won't have a bucket anyway)
        if (exclude == ExcludeFromSignature.PATH) {
          if (addr.startsWith(osgServicePath)) {
            addr = addr.substring(osgServicePath.length(), addr.length());
          } else if (addr.startsWith(ObjectStorageProperties.LEGACY_WALRUS_SERVICE_PATH)) {
            addr = addr.substring(ObjectStorageProperties.LEGACY_WALRUS_SERVICE_PATH.length(), addr.length());
          }
        }
      }

      // Get the path part, up to the ?
      key = addr.split("\\?", 2)[0];
      if (!Strings.isNullOrEmpty(key)) {
        addrString.append(key);
      } else {
        addrString.append("/");
      }

      List<String> canonicalSubresources = new ArrayList<>();
      for (String queryParam : httpRequest.getParameters().keySet()) {
        try {
          ObjectStorageProperties.SubResource.valueOf(queryParam);
          canonicalSubresources.add(queryParam);
        } catch (IllegalArgumentException e) {
          // Skip. Not in the set.
        }
        try {
          if (ObjectStorageProperties.ResponseHeaderOverrides.fromString(queryParam) != null) {
            canonicalSubresources.add(queryParam);
          }
        } catch (IllegalArgumentException e) {
          // Skip. Not in the set.
        }
      }

      if (canonicalSubresources.size() > 0) {
        Collections.sort(canonicalSubresources);
        String value;
        addrString.append("?");
        // Add resources to canonical string
        for (String subResource : canonicalSubresources) {
          value = httpRequest.getParameters().get(subResource);
          addrString.append(subResource);
          // Query values are not URL-decoded, the signature should have them exactly as in the URI
          if (!Strings.isNullOrEmpty(value)) {
            addrString.append("=").append(value);
          }
          addrString.append("&");
        }

        // Remove trailng '&' if found
        if (addrString.charAt(addrString.length() - 1) == '&') {
          addrString.deleteCharAt(addrString.length() - 1);
        }
      }

      return addrString.toString();
    } catch (S3Exception e) {
      throw e;
    } catch (Exception e) {
      // Anything unexpected...
      throw new InternalErrorException(e);
    }
  }

  /**
   * Query params are included in cases of Query-String/Presigned-url auth where they are considered just like headers.
   */
  static String buildCanonicalHeaders(MappingHttpRequest httpRequest, boolean includeQueryParams) {
    String result = "";
    Set<String> headerNames = httpRequest.getHeaderNames();
    TreeMap<String, String> amzHeaders = new TreeMap<>();
    for (String headerName : headerNames) {
      String headerNameString = headerName.toLowerCase().trim();
      if (headerNameString.startsWith("x-amz-")) {
        String value = httpRequest.getHeader(headerName).trim();
        String[] parts = value.split("\n");
        value = "";
        for (String part : parts) {
          part = part.trim();
          value += part + " ";
        }
        value = value.trim();
        if (amzHeaders.containsKey(headerNameString)) {
          String oldValue = amzHeaders.remove(headerNameString);
          oldValue += "," + value;
          amzHeaders.put(headerNameString, oldValue);
        } else {
          amzHeaders.put(headerNameString, value);
        }
      }
    }

    if (includeQueryParams) {
      // For query-string auth, header values may include 'x-amz-*' that need to be signed
      for (String paramName : httpRequest.getParameters().keySet()) {
        processHeaderValue(paramName, httpRequest.getParameters().get(paramName), amzHeaders);
      }
    }

    // Build the canonical string
    for (Map.Entry<String, String> entry : amzHeaders.entrySet()) {
      result += entry.getKey() + ":" + entry.getValue() + "\n";
    }
    return result;
  }

  /**
   * Gets and validates a date obtained from an Expires parameter.
   */
  static String getAndValidateExpiresFromParameters(Map<String, String> parameters) throws InvalidSecurityException, AccessDeniedException {
    String expires = parameters.remove(SecurityParameter.Expires.toString().toLowerCase());
    if (expires == null)
      throw new InvalidSecurityException("Expires parameter must be specified.");

    // Assert not expired
    Long expireTime;
    try {
      expireTime = Long.parseLong(expires);
    } catch (NumberFormatException e) {
      throw new AccessDeniedException(null, "Invalid Expires parameter.");
    }

    Long currentTime = new Date().getTime() / 1000;
    if (currentTime > expireTime)
      throw new AccessDeniedException(null, "Cannot process request. Expired.");
    return expires;
  }

  private static void processHeaderValue(String name, String value, Map<String, String> aggregatingMap) {
    String headerNameString = name.toLowerCase().trim();
    if (headerNameString.startsWith("x-amz-")) {
      value = value.trim();
      String[] parts = value.split("\n");
      value = "";
      for (String part : parts) {
        part = part.trim();
        value += part + " ";
      }
      value = value.trim();
      if (aggregatingMap.containsKey(headerNameString)) {
        String oldValue = aggregatingMap.remove(headerNameString);
        oldValue += "," + value;
        aggregatingMap.put(headerNameString, oldValue);
      } else {
        aggregatingMap.put(headerNameString, value);
      }
    }
  }
}
