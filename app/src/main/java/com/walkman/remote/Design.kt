package com.walkman.remote

/**
 * ВСЕ РАЗМЕРЫ И КООРДИНАТЫ ДИЗАЙНА В ОДНОМ МЕСТЕ.
 *
 * Числа взяты из макета Figma, кадр 720 x 1280 пикселей.
 * Если нужно подвинуть или изменить элемент, правится число здесь.
 */
object Design {

    // ---- Размер кадра из Figma ----
    const val W = 720f
    const val H = 1280f

    // ---- Цвета ----
    // Акцентный цвет по умолчанию (потом его можно менять кругом "C")
    val DEFAULT_ACCENT: Int = 0xFFF13F2B.toInt()

    // ---- Линии и шрифты ----
    const val LINE = 2f          // толщина всех белых линий
    const val MONO_SIZE = 28f    // Roboto Mono: цифры, B, M, SD
    const val FLEX_SIZE = 30f    // Roboto Flex: название песни и группы
    const val C_SIZE = 20f       // буква "C" на круглой кнопке

    // ---- Строки экрана (границы по вертикали) ----
    const val TOP_H = 52f            // верхняя строка B / M / SD
    const val DISC_BOTTOM = 772f     // низ области с диском
    const val TITLE_BOTTOM = 920f    // низ строки с названием

    // ---- Верхняя строка ----
    const val CELL_W = 240f          // ширина каждой из трёх ячеек
    const val PAD = 20f              // отступ текста от края ячейки
    const val TOP_BASE = 36f         // линия, на которой стоит текст
    const val SD_X_CENTER = 28f      // центр крестика "нет SD" от левого края ячейки
    const val SD_X_CENTER_Y = 25f
    const val SD_X_HALF = 9.2f       // половина размера крестика

    // ---- Диск ----
    const val DISC_CX = 360f
    const val DISC_CY = 412f
    const val DISC_R = 359f          // внешний круг (толщина линии добавляет ещё 1 px)
    const val COVER_R = 215f         // внутренний круг с обложкой (диаметр 432 px)
    const val DOT_R = 21.5f          // красная точка в центре
    const val TIMER_BASE = 90f       // линия текста для таймера + и -
    const val COUNTER_BASE = 747f    // линия текста для "1/10"
    const val TIMER_PAD = 20f

    // ---- Круглая кнопка "C" ----
    const val C_CX = 660f
    const val C_CY = 708f
    const val C_R = 36f

    // ---- Строка с названием и стрелками ----
    const val ARROW_L_END = 144f     // правая граница левой кнопки
    const val ARROW_R_START = 576f   // левая граница правой кнопки
    const val ARROW_L_X = 45f        // положение левой стрелки (левый верхний угол иконки)
    const val ARROW_R_X = 633f       // положение правой стрелки
    const val ARROW_Y = 805f
    const val TEXT_X = 166f          // левый край названия
    const val TEXT_MAX_W = 388f      // дальше текст обрезается многоточием
    const val TITLE_BASE = 833f      // линия текста названия песни
    const val ARTIST_BASE = 877f     // линия текста названия группы

    // ---- Громкость (полукруг внизу) ----
    const val VOL_CX = 360f
    const val VOL_CY = 1280f         // центр полукруга лежит на нижнем краю экрана
    const val VOL_R = 359f
    const val VOL_INNER_R = 215f
    const val VOL_TEXT_BASE = 1199f

    // ---- Громкость: чувствительность ----
    // 1 = вся шкала 0..100 за один проход по полукругу, 2 = за половину пути
    const val VOLUME_GAIN = 2f

    // ---- Заглушка обложки от плеера Sony (картинка с нотой) ----
    // Серая картинка с низким цветом и контрастом считается заглушкой и скрывается.
    // Если настоящие чёрно-белые обложки пропадают, уменьшите числа.
    // Если заглушка всё ещё видна, увеличьте.
    const val PLACEHOLDER_MAX_CHROMA = 25    // насколько картинка "цветная" (0..255)
    const val PLACEHOLDER_MAX_RANGE = 140    // разница между тёмным и светлым (0..255)
    const val CLONG_PRESS_MS = 700L          // долгое нажатие на "C" показывает данные об обложке

    // ---- Поведение ----
    const val SCRUB_MS_PER_TURN = 30000f   // полный оборот диска = 30 секунд
    const val DISC_DEG_PER_MS = 0.06f      // скорость вращения диска при игре
    const val LONG_PRESS_MS = 500L         // сколько держать стрелку до перемотки
    const val SEEK_STEP_MS = 5000L         // шаг перемотки при удержании
    const val SEEK_REPEAT_MS = 300L        // как часто повторять шаг
}
