# com.cdsoftware.paperless

- Copyright: 2026 https://www.casadelsoftware.com
- Repository: https://github.com/lcdsoftware/com.cdsoftware.paperless
- License: GPL 2

## Description

An iDempiere 12 OSGi storage-provider integration with Paperless-ngx. It supplies attachment and archive storage services that upload documents to Paperless-ngx, retrieve them when iDempiere loads a record, and request remote deletion. iDempiere retains document references in its attachment or archive data.

## Contributors

- 2026 Javier Galindo <javiergalindo@casadelsoftware.com>.

## Components

- iDempiere Plugin [com.cdsoftware.paperless](com.cdsoftware.paperless)
- iDempiere Unit Test Fragment [com.cdsoftware.paperless.test](com.cdsoftware.paperless.test)

## Prerequisites

- Java 17, commands `java` and `javac`.
- iDempiere 12.
- An accessible Paperless-ngx service with document API permissions.
- An iDempiere Storage Provider record configured with the Paperless method, service URL, username and password.

## Features/Documentation

### Source Structure

```text
com.cdsoftware.paperless/src
└── com
    └── cdsoftware
        └── paperless
            ├── base
            │   ├── BundleInfo.java
            │   ├── CustomCallout.java
            │   ├── CustomEvent.java
            │   ├── CustomForm.java
            │   └── CustomProcess.java
            ├── component
            │   ├── CalloutFactory.java
            │   ├── EventFactory.java
            │   ├── FormFactory.java
            │   ├── ModelFactory.java
            │   └── ProcessFactory.java
            ├── model
            │   ├── ArchivePaperless.java
            │   └── AttachmentPaperless.java
            └── util
                ├── DMSConfig.java
                ├── FileTemplateBuilder.java
                ├── KeyValueLogger.java
                ├── PaperlessUtil.java
                ├── SqlBuilder.java
                └── TimestampUtil.java
com.cdsoftware.paperless.test/src
└── com
    └── cdsoftware
        └── paperless
            ├── test
            │   ├── assertion
            │   │   └── Annotations.java
            │   └── util
            │       ├── RandomTestUtil.java
            │       └── ReflectionTestUtil.java
            └── util
                ├── FileTemplateBuilderTest.java
                ├── KeyValueLoggerTest.java
                ├── SqlBuilderTest.java
                └── TimestampUtilTest.java
```

### Storage Services

| Service | iDempiere interface | Functional behavior |
| --- | --- | --- |
| `AttachmentPaperless` | `IAttachmentStore` | Uploads attachment entries using the Paperless-ngx document API. Stores returned references in a ZIP manifest within the iDempiere attachment binary data and sets the attachment title to `zip`. On load, it downloads each referenced document and reconstructs the attachment entries. It also handles entry and whole-attachment deletion. |
| `ArchivePaperless` | `IArchiveStore` | Uploads archive content and stores the returned Paperless document reference as UTF-8 bytes in the archive binary data. On load, it downloads the referenced document. It offers remote archive deletion; save is immediate and flush is a no-op. |

Both services are registered by OSGi Declarative Services descriptors in `OSGI-INF/attachmentpaperless.xml` and `OSGI-INF/archivepaperless.xml`. Each registers the storage method `Paperless` with ranking `100`. The bundle also registers annotation-based factories, but this source tree contains no concrete process, callout, event or form implementations.

### Paperless-ngx API and Configuration

`PaperlessUtil` reads the Storage Provider URL, username, password and folder value. It encodes `username:password` for HTTP Basic authentication. Uploads use `/api/documents/post_document/`; task polling uses `/api/tasks/`; downloads use `/api/documents/{id}/download/`; deletes use `/api/documents/{id}/`. The configured folder is passed to the upload helper but is currently unused there, so this plugin does not organize uploaded documents into that folder.

For this implementation, configure the Storage Provider URL with a trailing `/`. Upload and delete helpers normalize their base URL, while the download helper concatenates the URL and `api/documents/...` directly. Check that credentials can upload, download and delete documents in the target Paperless-ngx instance.

## Instructions

1. Configure and test a Paperless-ngx endpoint and an account with the required document API permissions.
2. Install the `com.cdsoftware.paperless` OSGi bundle in iDempiere 12 and refresh or restart the runtime so its two storage services register.
3. Create or update an iDempiere Storage Provider record for method `Paperless`. Set its URL with a trailing `/`, username and password. The folder setting has no effect on uploads in this implementation.
4. Select that provider for the attachment or archive storage configuration that you intend to use. Verify a controlled upload, reload and delete through the iDempiere UI and the Paperless-ngx API before operational use.

## Extra Links

- [Paperless-ngx](https://docs.paperless-ngx.com/)
- [iDempiere](https://www.idempiere.org/)
