/*------------------------------------------------------------------------------
 **     Ident: Delivery Center Java
 **    Author: rene
 ** Copyright: (c) 9 jun. 2014 Sogeti Nederland B.V. All Rights Reserved.
 **------------------------------------------------------------------------------
 ** Sogeti Nederland B.V.            |  No part of this file may be reproduced  
 ** Distributed Software Engineering |  or transmitted in any form or by any        
 ** Lange Dreef 17                   |  means, electronic or mechanical, for the      
 ** 4131 NJ Vianen                   |  purpose, without the express written    
 ** The Netherlands                  |  permission of the copyright holder.
 *------------------------------------------------------------------------------
 */
package nl.sogeti.android.gpstracker.util;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import nl.sogeti.android.gpstracker.actions.tasks.GpxCreator;
import nl.sogeti.android.gpstracker.actions.utils.ProgressListener;
import nl.sogeti.android.gpstracker.db.GPStracking.Tracks;
import nl.sogeti.android.gpstracker.viewer.LoggerMap;
import android.app.Activity;
import android.content.ContentUris;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveApi.ContentsResult;
import com.google.android.gms.drive.DriveApi.MetadataBufferResult;
import com.google.android.gms.drive.DriveFile;
import com.google.android.gms.drive.DriveFile.DownloadProgressListener;
import com.google.android.gms.drive.DriveFolder;
import com.google.android.gms.drive.DriveFolder.DriveFileResult;
import com.google.android.gms.drive.DriveFolder.DriveFolderResult;
import com.google.android.gms.drive.DriveId;
import com.google.android.gms.drive.Metadata;
import com.google.android.gms.drive.MetadataBuffer;
import com.google.android.gms.drive.MetadataChangeSet;
import com.google.android.gms.drive.query.Filters;
import com.google.android.gms.drive.query.Query;
import com.google.android.gms.drive.query.SearchableField;
import com.google.common.io.ByteStreams;

/**
 * Creates backups of tracks to Google Drive
 * 
 * @author rene (c) 9 jun. 2014, Sogeti B.V.
 */
public class DriveBackup implements ConnectionCallbacks, OnConnectionFailedListener, ProgressListener, DownloadProgressListener
{

   private static final String MIME_TYPE = "application/gpx";
   private static final String TAG = "DriveBackup";
   private static final String FOLDER_NAME = "OpenGPSTracker";
   private Activity activity;
   private GoogleApiClient googleApiClient;
   private DriveId backupFolderId;
   private MetadataBuffer fileList;
   private Uri localFile;
   private DriveFile remoteFile;

   public DriveBackup(LoggerMap activity)
   {
      this.activity = activity;
   }

   @Override
   public void onConnectionFailed(ConnectionResult connectionResult)
   {
      if (connectionResult.hasResolution())
      {
         try
         {
            connectionResult.startResolutionForResult(activity, LoggerMap.RESOLVE_CONNECTION_REQUEST_CODE);
         }
         catch (IntentSender.SendIntentException e)
         {
            // Unable to resolve, message user appropriately
         }
      }
      else
      {
         GooglePlayServicesUtil.getErrorDialog(connectionResult.getErrorCode(), activity, 0).show();
      }
   }

   @Override
   public void onConnected(Bundle bundle)
   {
      Log.d(TAG, "Start backup");
      getBackupFolder();
   }

   private void getBackupFolder()
   {
      ResultCallback<MetadataBufferResult> rootFileListing = new RootListingCallback();
      Drive.DriveApi.getRootFolder(googleApiClient).listChildren(googleApiClient).setResultCallback(rootFileListing);
   }

   private void retrieveCompletedBackups()
   {
      DriveFolder folder = Drive.DriveApi.getFolder(googleApiClient, backupFolderId);
      Query query = new Query.Builder().addFilter(Filters.eq(SearchableField.MIME_TYPE, MIME_TYPE)).build();
      ResultCallback<MetadataBufferResult> gpxListingRetrievedCallback = new GpxListingRetrievedCallback();
      folder.queryChildren(googleApiClient, query).setResultCallback(gpxListingRetrievedCallback);
   }

   private void backupNextTrack()
   {
      String order = Tracks._ID;
      String[] projection = new String[] { Tracks._ID, Tracks.NAME };
      Cursor cursor = null;
      long trackId;
      String name;
      try
      {
         cursor = activity.getContentResolver().query(Tracks.CONTENT_URI, projection, null, null, order);
         if (cursor.moveToFirst())
         {
            do
            {
               trackId = cursor.getLong(0);
               name = cursor.getString(1);
               if (!isStoredOnDrive(trackId, name))
               {
                  Uri trackUri = ContentUris.withAppendedId(Tracks.CONTENT_URI, trackId);
                  String fileName = toDriveTitle(trackId, name);
                  new GpxCreator(activity, trackUri, fileName, true, this).execute();
               }
            }
            while (cursor.moveToNext());
         }
      }
      finally
      {
         if (cursor != null)
         {
            cursor.close();
         }
      }
   }

   private void createTrackContents()
   {
      Drive.DriveApi.newContents(googleApiClient).setResultCallback(new ContentsCreate());
   }

   private String fileName(long trackId, String name)
   {
      return toDriveTitle(trackId, name);
   }

   private boolean isStoredOnDrive(long id, String name)
   {
      String title = fileName(id, name);
      for (Metadata file : fileList)
      {
         if (title.equals(file.getTitle()))
         {
            return true;
         }
      }
      return false;
   }

   private String toDriveTitle(long id, String name)
   {
      return String.format("%d_%s", id, name);
   }

   @Override
   public void onConnectionSuspended(int arg0)
   {
      Log.d(TAG, "Stop backup");
   }

   public void setGoogleApiClient(GoogleApiClient googleApiClient)
   {
      this.googleApiClient = googleApiClient;
   }

   @Override
   public void setIndeterminate(boolean indeterminate)
   {
      Log.d(TAG, "setIndeterminate(indeterminate" + indeterminate + ")");
      activity.setProgressBarIndeterminate(indeterminate);
   }

   @Override
   public void started()
   {
      Log.d(TAG, "started()");
      activity.setProgressBarVisibility(true);
   }

   @Override
   public void setProgress(int value)
   {
      Log.d(TAG, "setProgress(value" + value + ")");
      activity.setProgress(value);
   }

   @Override
   public void finished(Uri result)
   {
      Log.d(TAG, "finished(result" + result + ")");
      activity.setProgressBarVisibility(false);
      localFile = result;
      createTrackContents();
   }

   @Override
   public void showError(String task, String errorMessage, Exception exception)
   {
      Log.d(TAG, "showError(errorMessage" + errorMessage + ")");
      //TODO continue
   }

   @Override
   public void onProgress(long arg0, long arg1)
   {
      // TODO Auto-generated method stub

   }

   private class RootListingCallback implements ResultCallback<MetadataBufferResult>
   {

      @Override
      public void onResult(MetadataBufferResult result)
      {
         if (result.getStatus().isSuccess())
         {
            for (Metadata meta : result.getMetadataBuffer())
            {
               if (meta.isFolder() && meta.isEditable() && FOLDER_NAME.equals(meta.getTitle()))
               {
                  backupFolderId = meta.getDriveId();
                  retrieveCompletedBackups();
                  break;
               }
            }
            result.getMetadataBuffer().close();

            if (backupFolderId == null)
            {
               MetadataChangeSet changeSet = new MetadataChangeSet.Builder().setTitle(FOLDER_NAME).build();
               FolderCreatedCallback folderCreatedCallback = new FolderCreatedCallback();
               Drive.DriveApi.getRootFolder(googleApiClient).createFolder(googleApiClient, changeSet).setResultCallback(folderCreatedCallback);
            }

         }
         else
         {
            Log.e(TAG, "Failed to list root-folder");
         }
      }
   }

   public class FolderCreatedCallback implements ResultCallback<DriveFolderResult>
   {

      @Override
      public void onResult(DriveFolderResult result)
      {
         if (result.getStatus().isSuccess())
         {
            backupFolderId = result.getDriveFolder().getDriveId();
            retrieveCompletedBackups();
         }
         else
         {
            Log.e(TAG, "Failed to create folder");
         }
      }
   }

   private class GpxListingRetrievedCallback implements ResultCallback<MetadataBufferResult>
   {

      @Override
      public void onResult(MetadataBufferResult result)
      {
         if (result.getStatus().isSuccess())
         {
            fileList = result.getMetadataBuffer();
            backupNextTrack();
         }
         else
         {
            Log.e(TAG, "Failed to list GPX files");
         }
         result.getMetadataBuffer().close();
      }

   }

   private class ContentsCreate implements ResultCallback<ContentsResult>
   {
      @Override
      public void onResult(ContentsResult result)
      {
         if (result.getStatus().isSuccess())
         {
            DriveFolder folder = Drive.DriveApi.getFolder(googleApiClient, backupFolderId);
            MetadataChangeSet changeSet = new MetadataChangeSet.Builder().setTitle(localFile.getLastPathSegment()).setMimeType(MIME_TYPE).setStarred(false).build();
            folder.createFile(googleApiClient, changeSet, result.getContents()).setResultCallback(new FileCallback());
         }
         else
         {
            Log.e(TAG, "Failed to list GPX files");
         }
      }
   }

   public class FileCallback implements ResultCallback<DriveFileResult>
   {

      @Override
      public void onResult(DriveFileResult result)
      {
         if (result.getStatus().isSuccess())
         {
            remoteFile = result.getDriveFile();
            remoteFile.openContents(googleApiClient, DriveFile.MODE_WRITE_ONLY, DriveBackup.this).setResultCallback(new ContentWriter());

         }
         else
         {
            Log.e(TAG, "Failed to create file");
         }
      }

   }

   public class ContentWriter implements ResultCallback<ContentsResult>
   {

      @Override
      public void onResult(ContentsResult results)
      {
         if (results.getStatus().isSuccess())
         {
            InputStream in = null;
            OutputStream out = null;
            try
            {
               in = activity.getContentResolver().openInputStream(localFile);
               out = results.getContents().getOutputStream();
               long count = ByteStreams.copy(in, out);
               Log.d(TAG, "Copied " + count + " bytes");
            }
            catch (FileNotFoundException e)
            {
               Log.e(TAG, "Failed to upload contents", e);
            }
            catch (IOException e)
            {
               Log.e(TAG, "Failed to upload contents", e);
            }
            finally
            {
               close(in);
               close(out);
            }
            remoteFile.commitAndCloseContents(googleApiClient, results.getContents()).setResultCallback(new FileWrittenCallback());
         }
         else
         {
            Log.e(TAG, "Failed to open file contents");
         }
      }

      public class FileWrittenCallback implements ResultCallback<Status>
      {

         @Override
         public void onResult(Status results)
         {
            if (results.getStatus().isSuccess())
            {
               Log.e(TAG, "Succes with uploading... " + localFile.getLastPathSegment());
               //TODO prep for next file 
               //backupNextTrack();
            }
            else
            {
               Log.e(TAG, "Failed to close the file contents");
            }
         }

      }

      private void close(Closeable closeable)
      {
         if (closeable != null)
         {
            try
            {
               closeable.close();
            }
            catch (IOException e)
            {
               Log.e(TAG, "Failed to close " + closeable);
            }
         }
      }
   }
}