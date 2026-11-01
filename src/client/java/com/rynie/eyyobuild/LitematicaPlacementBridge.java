package com.rynie.eyyobuild;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.io.File;
import java.nio.file.Path;

/** Optional reflection-only bridge to Litematica. */
public final class LitematicaPlacementBridge {
    private LitematicaPlacementBridge() {}

    public static Result findSelectedPlacement(Path expectedSchematic) {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) {
            return Result.failure("Litematica yüklü değil. Şemayı Litematica'da yerleştirip seç.");
        }

        try {
            Class<?> dataManagerClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object manager = dataManagerClass.getMethod("getSchematicPlacementManager").invoke(null);
            if (manager == null) {
                return Result.failure("Litematica yerleşim yöneticisi bulunamadı.");
            }

            Object placement = manager.getClass().getMethod("getSelectedSchematicPlacement").invoke(manager);
            if (placement == null) {
                return Result.failure("Litematica'da seçili bir şema yerleşimi yok. Yerleşimi seç (beyaz çerçeve).");
            }

            Object enabled = placement.getClass().getMethod("isEnabled").invoke(placement);
            if (enabled instanceof Boolean value && !value) {
                return Result.failure("Seçili Litematica yerleşimi devre dışı.");
            }

            Object fileValue = placement.getClass().getMethod("getSchematicFile").invoke(placement);
            Path placedFile = fileValue instanceof File file ? file.toPath()
                    : fileValue instanceof Path path ? path : null;
            if (placedFile == null) {
                return Result.failure("Seçili yerleşim bir dosyaya bağlı değil. Şemayı .litematic dosyasından yeniden yükle.");
            }

            Path expected = normalize(expectedSchematic);
            Path actual = normalize(placedFile);
            if (!expected.equals(actual)) {
                return Result.failure("EYYOBUILD'de seçili dosya ile Litematica'daki seçili yerleşim aynı değil.");
            }

            Object originValue = placement.getClass().getMethod("getOrigin").invoke(placement);
            if (!(originValue instanceof BlockPos origin)) {
                return Result.failure("Litematica yerleşim koordinatı okunamadı.");
            }

            String rotation = enumName(placement.getClass().getMethod("getRotation").invoke(placement));
            String mirror = enumName(placement.getClass().getMethod("getMirror").invoke(placement));
            String name = String.valueOf(placement.getClass().getMethod("getName").invoke(placement));
            return Result.success(origin, rotation, mirror, name);
        } catch (ClassNotFoundException exception) {
            return Result.failure("Litematica API'si bulunamadı. Litematica sürümünü kontrol et.");
        } catch (ReflectiveOperationException | LinkageError exception) {
            return Result.failure("Litematica yerleşimi okunamadı: " + exception.getClass().getSimpleName());
        } catch (RuntimeException exception) {
            return Result.failure("Litematica yerleşimi okunamadı: " + safeMessage(exception));
        }
    }

    private static Path normalize(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (Exception ignored) {
            return absolute;
        }
    }

    private static String enumName(Object value) {
        return value instanceof Enum<?> enumValue ? enumValue.name() : String.valueOf(value);
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    public record Result(boolean success, BlockPos origin, String rotation, String mirror,
                         String placementName, String message) {
        public static Result success(BlockPos origin, String rotation, String mirror, String placementName) {
            return new Result(true, origin, rotation, mirror, placementName, "");
        }

        public static Result failure(String message) {
            return new Result(false, null, "NONE", "NONE", "", message);
        }
    }
}
