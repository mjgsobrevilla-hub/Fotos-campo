# Campo Cámara

Cámara para salidas de campo. Cada foto se guarda en su carpeta según estación, unidad muestral y tipo, con marca de agua (fecha, UTM y brújula) y queda registrada en una base de datos.

**Requisitos:** Android 10 o superior.

## Instalar

1. En el celular, abre la página del repositorio → **Releases** → **CampoCamara.apk**.
2. Al abrir el archivo, Android pedirá permitir "instalar apps desconocidas" para el navegador o WhatsApp. Acepta.
3. Para actualizar, instala el APK nuevo encima. Las fotos y el registro se conservan.

También puedes descargar el APK en la PC y mandarlo por WhatsApp o Drive a los consultores.

## Uso en campo

1. **⚙ Proyecto:** nombre de la salida, consultor, estaciones (por ejemplo `A, B, C`) y número de unidades muestrales.
2. **Arriba**, toca la estación, la unidad muestral (UM) y el tipo. Si quieres, escribe un nombre.
   - La selección se mantiene entre fotos.
   - Si el nombre queda vacío, se usa uno automático: `A_UM1_PAN_0001.jpg`.
3. **Disparador blanco:** toma la foto. **● Video** cambia a modo video, donde el disparador es rojo.
4. **Pellizca** la pantalla para hacer zoom. **Toca** un punto para enfocarlo. Toca el `1.0x` para volver al zoom normal.
5. Toca la **línea de estado** (la primera línea de arriba) para ocultar o mostrar las filas.
   - Esa línea muestra la precisión del GPS. **Verde:** ±10 m o menos. **Amarillo:** ±30 m o menos. **Rojo:** peor, o sin señal.
6. **▤ Resumen:** cuántas fotos hay por estación, UM y tipo. Así se ve qué falta antes de irse.
7. **⇪ Exportar:** genera el registro para Excel (`.csv`) y el mapa para Google Earth (`.kml`), y permite compartirlos.

## Dónde quedan los archivos

```
Imágenes (Pictures)/CampoFotos/<Proyecto>/<Estación>/UM<n>/
    1_Panoramicas/  2_Metodologia/  3_Afectaciones/  4_Especies/  5_Indicadores/
Descargas (Download)/CampoFotos/   ← registro .csv y mapa .kml
```

Para pasarlas a la PC: conecta el celular por USB y copia la carpeta `Pictures/CampoFotos`.

## Marca de agua

| Tipo | Marca |
|---|---|
| Panorámicas | Fecha + UTM + dirección (brújula) |
| Metodología, Afectaciones, Indicadores | Fecha + UTM |
| Especies | Según el ajuste del proyecto (ver abajo) |

Opciones para las fotos de especies:

- **Doble** (por defecto): guarda la foto original intacta, sin recomprimir, y además una copia con marca que termina en `_MA.jpg`.
- **Franja:** pone los datos en una banda negra añadida *debajo* de la foto. No tapa nada.
- **Sin marca:** los datos quedan solo dentro de la foto (EXIF) y en el registro.

Todas las fotos llevan dentro del archivo (EXIF) la fecha, el GPS y la dirección de la cámara. Las fotos con marca se guardan con calidad JPEG 97 %, a la resolución completa de la cámara (hasta unos 12 MP).

**UTM:** WGS84 con la zona calculada automáticamente (en Perú, 17S, 18S o 19S).

**Brújula:**

- Indica hacia dónde apunta la cámara, respecto al norte verdadero cuando hay GPS.
- Si aparece "calibra la brújula", mueve el celular dibujando un 8 en el aire.
- Aléjate de vehículos y objetos metálicos al medir.

## Registro (CSV)

Columnas: proyecto, consultor, estación, unidad muestral, tipo, medio (foto/video), variante, carpeta, archivo, fecha, hora, latitud, longitud, altitud, precisión, zona UTM, este, norte, dirección (grados y punto cardinal) y referencia del norte.

## Limitaciones de esta versión

- Los videos no llevan marca de agua. Sus datos (fecha, UTM, dirección) quedan en el registro.
- El registro vive dentro de la app. Si se desinstala la app, se pierde el registro, pero no las fotos. Conviene **exportar al terminar cada salida**.

## Compilación

Cada vez que se sube un cambio a la rama `main`, GitHub Actions compila el APK y lo publica en Releases (`.github/workflows/build.yml`). Proyecto en Kotlin con CameraX; no requiere Android Studio.
