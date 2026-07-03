# com.cdsoftware.paperless
- Copyright: 2026 https://www.casadelsoftware.com
- Repository: https://bitbucket.org/cdsoftware/com.cdsoftware.paperless.git
- License: GPL 2

## Description
The `com.cdsoftware.paperless` plugin is a custom extension for iDempiere. It extends standard system capabilities by providing database models, and Application Dictionary configurations (2Pack) to support customized business workflows.

## Contributors
- 2026 Casa del Software <info@casadelsoftware.com>

## Components
- iDempiere Plugin [com.cdsoftware.paperless](com.cdsoftware.paperless)
- iDempiere Unit Test Fragment [com.cdsoftware.paperless.test](com.cdsoftware.paperless.test)

## Prerequisites
- Java 11, commands `java` and `javac`.
- iDempiere 11

## Features/Documentation
### Source Structure
```
├── com/
        ├── cdsoftware/
            ├── paperless/
                ├── util/
                    ├── DMSConfig.java
                    ├── FileTemplateBuilder.java
                    ├── KeyValueLogger.java
                    ├── PaperlessUtil.java
                    ├── SqlBuilder.java
                    ├── TimestampUtil.java
                ├── model/
                    ├── ArchivePaperless.java
                    ├── AttachmentPaperless.java
                ├── base/
                    ├── BundleInfo.java
                    ├── CustomCallout.java
                    ├── CustomEvent.java
                    ├── CustomForm.java
                    ├── CustomProcess.java
                ├── component/
                    ├── CalloutFactory.java
                    ├── EventFactory.java
                    ├── FormFactory.java
                    ├── ModelFactory.java
                    ├── ProcessFactory.java
```




### Generated Models

| Model | Table | Functional role |
| --- | --- | --- |
| `AttachmentPaperless` | `AttachmentPaperless` | Implementation of {@link IAttachmentStore} for Paperless-ngx integration. Handles attachment storage operations using a ZIP manifest pattern to store Paperless document IDs as references. @version 1.0 |
| `ArchivePaperless` | `ArchivePaperless` | Implementation of {@link IArchiveStore} for Paperless-ngx integration. Handles archive storage operations by storing Paperless document IDs as references in the archive's binary data field. @version 1.0 |


### Application Dictionary Metadata (2Pack)

| Package / File Name | Purpose & Dictionary Configurations |
| --- | --- |
| `CalloutFactory.xml` | Metadata package containing Application Dictionary (AD) configurations. |
| `EventFactory.xml` | Metadata package containing Application Dictionary (AD) configurations. |
| `FormFactory.xml` | Metadata package containing Application Dictionary (AD) configurations. |
| `ModelFactory.xml` | Metadata package containing Application Dictionary (AD) configurations. |
| `ProcessFactory.xml` | Metadata package containing Application Dictionary (AD) configurations. |
| `archivepaperless.xml` | Metadata package containing Application Dictionary (AD) configurations. |
| `attachmentpaperless.xml` | Metadata package containing Application Dictionary (AD) configurations. |
| `xml-invoice.xml` | Metadata package containing Application Dictionary (AD) configurations. |


## Instructions
1. Deploy the `com.cdsoftware.paperless` OSGi bundle in your iDempiere environment.
2. Restart iDempiere and refresh OSGi bundles to register factories.
3. Configure dictionary and role access rules as needed.
