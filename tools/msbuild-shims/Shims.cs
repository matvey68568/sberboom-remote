using System;
using Microsoft.Build.Framework;
using Microsoft.Build.Utilities;

// Заглушки задач, которые в обычной сборке WinUI 3 приходят из Visual Studio
// (Microsoft.Build.AppxPackage.dll и Microsoft.Build.Packaging.Pri.Tasks.dll).
// Нам нужен `dotnet build` без VS: PRI-индексация и AppX-упаковка не требуются,
// потому что приложение распакованное (WindowsPackageType=None).
//
// Правило: принимаем ровно те параметры, которые передаёт Windows App SDK,
// и возвращаем нейтральные значения (тождество для списков файлов).

namespace Microsoft.Build.AppxPackage
{
    public class RemovePayloadDuplicates : Task
    {
        public ITaskItem[] Inputs { get; set; } = Array.Empty<ITaskItem>();
        public string Platform { get; set; }
        public string ProjectName { get; set; }
        public string VsTelemetrySession { get; set; }

        [Output]
        public ITaskItem[] Filtered { get; private set; } = Array.Empty<ITaskItem>();

        public override bool Execute()
        {
            Filtered = Inputs ?? Array.Empty<ITaskItem>();
            return true;
        }
    }

    public class ExpandPayloadDirectories : Task
    {
        public ITaskItem[] Inputs { get; set; } = Array.Empty<ITaskItem>();
        public ITaskItem[] TargetDirsToExclude { get; set; } = Array.Empty<ITaskItem>();
        public ITaskItem[] TargetFilesToExclude { get; set; } = Array.Empty<ITaskItem>();
        public string VsTelemetrySession { get; set; }

        [Output]
        public ITaskItem[] Expanded { get; private set; } = Array.Empty<ITaskItem>();

        public override bool Execute()
        {
            Expanded = Inputs ?? Array.Empty<ITaskItem>();
            return true;
        }
    }

    public class GetDefaultResourceLanguage : Task
    {
        public string DefaultLanguage { get; set; } = "ru-RU";
        public string SourceAppxManifest { get; set; }
        public string VsTelemetrySession { get; set; }

        [Output]
        public string DefaultResourceLanguage { get; private set; }

        public override bool Execute()
        {
            DefaultResourceLanguage = string.IsNullOrEmpty(DefaultLanguage) ? "en-US" : DefaultLanguage;
            return true;
        }
    }

    public class GetPackageArchitecture : Task
    {
        public string Platform { get; set; }
        public string ProjectArchitecture { get; set; }
        public string RecursiveProjectArchitecture { get; set; }
        public string VsTelemetrySession { get; set; }

        [Output]
        public string PackageArchitecture { get; private set; }

        public override bool Execute()
        {
            PackageArchitecture = string.IsNullOrEmpty(Platform) ? "x64" : Platform;
            return true;
        }
    }

    public class GetSdkFileFullPath : Task
    {
        public string FileName { get; set; }
        public string FileArchitecture { get; set; }
        public string SDKIdentifier { get; set; }
        public string SDKVersion { get; set; }
        public string TargetPlatformIdentifier { get; set; }
        public string TargetPlatformVersion { get; set; }
        public string TargetPlatformMinVersion { get; set; }
        public string TargetPlatformSdkRootOverride { get; set; }
        public bool RequireExeExtension { get; set; }
        public bool MSBuildExtensionsPath64Exists { get; set; }
        public string VsTelemetrySession { get; set; }

        [Output] public string ActualFullFilePath { get; private set; }
        [Output] public string ActualFileArchitecture { get; private set; }

        public override bool Execute()
        {
            ActualFileArchitecture = string.IsNullOrEmpty(FileArchitecture) ? "x64" : FileArchitecture;
            ActualFullFilePath = null; // «файла нет» — вызывающий код перейдёт на запасной путь
            return true;
        }
    }

    public class GetSdkPropertyValue : Task
    {
        public string PropertyName { get; set; }
        public string SDKIdentifier { get; set; }
        public string SDKVersion { get; set; }
        public string TargetPlatformIdentifier { get; set; }
        public string TargetPlatformVersion { get; set; }
        public string TargetPlatformMinVersion { get; set; }
        public string TargetPlatformSdkRootOverride { get; set; }
        public string VsTelemetrySession { get; set; }

        [Output]
        public string PropertyValue { get; private set; }

        public override bool Execute()
        {
            PropertyValue = null;
            return true;
        }
    }

    public class RemoveRedundantXamlFilesFromSdkPayload : Task
    {
        public override bool Execute() => true;
    }

    public class ValidateConfiguration : Task
    {
        public string Platform { get; set; }
        public string ProjectLanguage { get; set; }
        public string TargetPlatformIdentifier { get; set; }
        public string TargetPlatformVersion { get; set; }
        public string TargetPlatformMinVersion { get; set; }
        public string VsTelemetrySession { get; set; }

        public override bool Execute() => true;
    }
}

namespace Microsoft.Build.Packaging.Pri.Tasks
{
    public class ExpandPriContent : Task
    {
        public ITaskItem[] Inputs { get; set; } = Array.Empty<ITaskItem>();
        public string IntermediateDirectory { get; set; }
        public string MakePriExeFullPath { get; set; }
        public string MakePriExtensionPath { get; set; }
        public string AdditionalMakepriExeParameters { get; set; }
        public bool ExcludeXamlFromLibraryLayoutsWhenXbfIsPresent { get; set; }
        public string VsTelemetrySession { get; set; }

        [Output] public ITaskItem[] Expanded { get; private set; } = Array.Empty<ITaskItem>();
        [Output] public ITaskItem[] IntermediateFileWrites { get; private set; } = Array.Empty<ITaskItem>();

        public override bool Execute()
        {
            Expanded = Inputs ?? Array.Empty<ITaskItem>();
            return true;
        }
    }

    public class CreatePriFilesForPortableLibraries : Task
    {
        public ITaskItem[] ContentToIndex { get; set; } = Array.Empty<ITaskItem>();
        public string IntermediateDirectory { get; set; }
        public string IntermediateExtension { get; set; }
        public string MakePriExeFullPath { get; set; }
        public string MakePriExtensionPath { get; set; }
        public string AdditionalMakepriExeParameters { get; set; }
        public string AppxBundleAutoResourcePackageQualifiers { get; set; }
        public string DefaultResourceLanguage { get; set; }
        public string DefaultResourceQualifiers { get; set; }
        public string TargetPlatformIdentifier { get; set; }
        public string TargetPlatformVersion { get; set; }
        public bool SkipIntermediatePriGenerationForResourceFiles { get; set; }
        public string VsTelemetrySession { get; set; }

        [Output] public ITaskItem[] CreatedPriFiles { get; private set; } = Array.Empty<ITaskItem>();
        [Output] public ITaskItem[] IntermediateFileWrites { get; private set; } = Array.Empty<ITaskItem>();
        [Output] public ITaskItem[] UnprocessedReswFiles_DefaultLanguage { get; private set; } = Array.Empty<ITaskItem>();
        [Output] public ITaskItem[] UnprocessedReswFiles_OtherLanguages { get; private set; } = Array.Empty<ITaskItem>();

        public override bool Execute() => true;
    }

    public class GeneratePriConfigurationFiles : Task
    {
        public ITaskItem[] LayoutFiles { get; set; } = Array.Empty<ITaskItem>();
        public ITaskItem[] PRIResourceFiles { get; set; } = Array.Empty<ITaskItem>();
        public ITaskItem[] PriFiles { get; set; } = Array.Empty<ITaskItem>();
        public ITaskItem[] EmbedFiles { get; set; } = Array.Empty<ITaskItem>();
        public ITaskItem[] UnprocessedResourceFiles_OtherLanguages { get; set; } = Array.Empty<ITaskItem>();
        public string EmbedFileResfilePath { get; set; }
        public string ExcludedLayoutResfilesPath { get; set; }
        public string FilteredLayoutResfilesPath { get; set; }
        public string UnfilteredLayoutResfilesPath { get; set; }
        public string IntermediateExtension { get; set; }
        public string PriResfilesPath { get; set; }
        public string ResourcesResfilesPath { get; set; }
        public string VsTelemetrySession { get; set; }

        [Output]
        public ITaskItem[] AdditionalResourceResFiles { get; private set; } = Array.Empty<ITaskItem>();

        public override bool Execute() => true;
    }

    public class CreatePriConfigXmlForFullIndex : Task
    {
        public string PriConfigXmlPath { get; set; }
        public string PriConfigXmlDefaultSnippetPath { get; set; }
        public string DefaultResourceLanguage { get; set; }
        public string DefaultResourceQualifiers { get; set; }
        public string PriInitialPath { get; set; }
        public string LayoutResfilesPath { get; set; }
        public string PriResfilesPath { get; set; }
        public string ResourcesResfilesPath { get; set; }
        public string AdditionalResourceResFiles { get; set; }
        public string EmbedFileResfilePath { get; set; }
        public string IntermediateExtension { get; set; }
        public string TargetPlatformIdentifier { get; set; }
        public string TargetPlatformVersion { get; set; }
        public string VsTelemetrySession { get; set; }

        public override bool Execute() => true;
    }

    public class CreatePriConfigXmlForSplitting : Task
    {
        public override bool Execute() => true;
    }

    public class CreatePriConfigXmlForMainPackageFileMap : Task
    {
        public override bool Execute() => true;
    }

    public class GenerateMainPriConfigurationFile : Task
    {
        public override bool Execute() => true;
    }

    public class UpdateMainPackageFileMap : Task
    {
        public override bool Execute() => true;
    }

    public class GenerateProjectPriFile : Task
    {
        public string PriConfigXmlPath { get; set; }
        public string OutputFileName { get; set; }
        public string ProjectDirectory { get; set; }
        public string ProjectPriIndexName { get; set; }
        public string QualifiersPath { get; set; }
        public string MultipleQualifiersPerDimensionFoundPath { get; set; }
        public string IntermediateExtension { get; set; }
        public string MakePriExeFullPath { get; set; }
        public string MakePriExtensionPath { get; set; }
        public string AdditionalMakepriExeParameters { get; set; }
        public string AppxBundleAutoResourcePackageQualifiers { get; set; }
        public bool IndexFilesForQualifiersCollection { get; set; }
        public bool InsertReverseMap { get; set; }
        public string VsTelemetrySession { get; set; }

        public override bool Execute() => true;
    }

    public class RemoveDuplicatePriFiles : Task
    {
        public ITaskItem[] Inputs { get; set; } = Array.Empty<ITaskItem>();
        public string Platform { get; set; }
        public string VsTelemetrySession { get; set; }

        [Output]
        public ITaskItem[] Filtered { get; private set; } = Array.Empty<ITaskItem>();

        public override bool Execute()
        {
            Filtered = Inputs ?? Array.Empty<ITaskItem>();
            return true;
        }
    }
}
