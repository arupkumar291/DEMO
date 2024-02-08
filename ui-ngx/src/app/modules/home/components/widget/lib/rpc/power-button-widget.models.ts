///
/// Copyright © 2016-2024 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { BackgroundSettings, BackgroundType } from '@shared/models/widget-settings.models';
import { AttributeScope } from '@shared/models/telemetry/telemetry.models';
import {
  DataToValueType,
  GetValueAction,
  GetValueSettings,
  SetValueAction,
  SetValueSettings,
  ValueToDataType
} from '@shared/models/action-widget-settings.models';
import { Circle, Element, G, Gradient, Runner, Stop, Svg, Text, Timeline } from '@svgdotjs/svg.js';
import tinycolor from 'tinycolor2';
import { WidgetContext } from '@home/models/widget-component.models';

export enum PowerButtonLayout {
  default = 'default',
  simplified = 'simplified',
  outlined = 'outlined',
  default_volume = 'default_volume',
  simplified_volume = 'simplified_volume',
  outlined_volume = 'outlined_volume'
}

export const powerButtonLayouts = Object.keys(PowerButtonLayout) as PowerButtonLayout[];

export const powerButtonLayoutTranslations = new Map<PowerButtonLayout, string>(
  [
    [PowerButtonLayout.default, 'widgets.power-button.layout-default'],
    [PowerButtonLayout.simplified, 'widgets.power-button.layout-simplified'],
    [PowerButtonLayout.outlined, 'widgets.power-button.layout-outlined'],
    [PowerButtonLayout.default_volume, 'widgets.power-button.layout-default-volume'],
    [PowerButtonLayout.simplified_volume, 'widgets.power-button.layout-simplified-volume'],
    [PowerButtonLayout.outlined_volume, 'widgets.power-button.layout-outlined-volume']
  ]
);

export const powerButtonLayoutImages = new Map<PowerButtonLayout, string>(
  [
    [PowerButtonLayout.default, 'assets/widget/power-button/default-layout.svg'],
    [PowerButtonLayout.simplified, 'assets/widget/power-button/simplified-layout.svg'],
    [PowerButtonLayout.outlined, 'assets/widget/power-button/outlined-layout.svg'],
    [PowerButtonLayout.default_volume, 'assets/widget/power-button/default-volume-layout.svg'],
    [PowerButtonLayout.simplified_volume, 'assets/widget/power-button/simplified-volume-layout.svg'],
    [PowerButtonLayout.outlined_volume, 'assets/widget/power-button/outlined-volume-layout.svg']
  ]
);

export interface PowerButtonWidgetSettings {
  initialState: GetValueSettings<boolean>;
  disabledState: GetValueSettings<boolean>;
  onUpdateState: SetValueSettings;
  offUpdateState: SetValueSettings;
  layout: PowerButtonLayout;
  mainColorOn: string;
  backgroundColorOn: string;
  mainColorOff: string;
  backgroundColorOff: string;
  mainColorDisabled: string;
  backgroundColorDisabled: string;
  background: BackgroundSettings;
}

export const powerButtonDefaultSettings: PowerButtonWidgetSettings = {
  initialState: {
    action: GetValueAction.EXECUTE_RPC,
    defaultValue: false,
    executeRpc: {
      method: 'getState',
      requestTimeout: 5000,
      requestPersistent: false,
      persistentPollingInterval: 1000
    },
    getAttribute: {
      key: 'state',
      scope: null
    },
    getTimeSeries: {
      key: 'state'
    },
    dataToValue: {
      type: DataToValueType.NONE,
      compareToValue: true,
      dataToValueFunction: '/* Should return boolean value */\nreturn data;'
    }
  },
  disabledState: {
    action: GetValueAction.DO_NOTHING,
    defaultValue: false,
    getAttribute: {
      key: 'state',
      scope: null
    },
    getTimeSeries: {
      key: 'state'
    },
    dataToValue: {
      type: DataToValueType.NONE,
      compareToValue: true,
      dataToValueFunction: '/* Should return boolean value */\nreturn data;'
    }
  },
  onUpdateState: {
    action: SetValueAction.EXECUTE_RPC,
    executeRpc: {
      method: 'setState',
      requestTimeout: 5000,
      requestPersistent: false,
      persistentPollingInterval: 1000
    },
    setAttribute: {
      key: 'state',
      scope: AttributeScope.SHARED_SCOPE
    },
    putTimeSeries: {
      key: 'state'
    },
    valueToData: {
      type: ValueToDataType.CONSTANT,
      constantValue: true,
      valueToDataFunction: '/* Convert input boolean value to RPC parameters or attribute/time-series value */\nreturn value;'
    }
  },
  offUpdateState: {
    action: SetValueAction.EXECUTE_RPC,
    executeRpc: {
      method: 'setState',
      requestTimeout: 5000,
      requestPersistent: false,
      persistentPollingInterval: 1000
    },
    setAttribute: {
      key: 'state',
      scope: AttributeScope.SHARED_SCOPE
    },
    putTimeSeries: {
      key: 'state'
    },
    valueToData: {
      type: ValueToDataType.CONSTANT,
      constantValue: false,
      valueToDataFunction: '/* Convert input boolean value to RPC parameters or attribute/time-series value */ \n return value;'
    }
  },
  layout: PowerButtonLayout.default,
  mainColorOn: '#3F52DD',
  backgroundColorOn: '#FFFFFF',
  mainColorOff: '#A2A2A2',
  backgroundColorOff: '#FFFFFF',
  mainColorDisabled: 'rgba(0,0,0,0.12)',
  backgroundColorDisabled: '#FFFFFF',
  background: {
    type: BackgroundType.color,
    color: '#fff',
    overlay: {
      enabled: false,
      color: 'rgba(255,255,255,0.72)',
      blur: 3
    }
  }
};

interface PowerButtonColor {
  hex: string;
  opacity: number;
}

type PowerButtonState = 'on' | 'off' | 'disabled';

interface PowerButtonColorState {
  mainColor: PowerButtonColor;
  backgroundColor: PowerButtonColor;
}

type PowerButtonShapeColors = Record<PowerButtonState, PowerButtonColorState>;

const createPowerButtonShapeColors = (settings: PowerButtonWidgetSettings): PowerButtonShapeColors => {
  const mainColorOn = tinycolor(settings.mainColorOn);
  const backgroundColorOn = tinycolor(settings.backgroundColorOn);
  const mainColorOff = tinycolor(settings.mainColorOff);
  const backgroundColorOff = tinycolor(settings.backgroundColorOff);
  const mainColorDisabled = tinycolor(settings.mainColorDisabled);
  const backgroundColorDisabled = tinycolor(settings.backgroundColorDisabled);
  return {
    on: {
      mainColor: {hex: mainColorOn.toHexString(), opacity: mainColorOn.getAlpha()},
      backgroundColor: {hex: backgroundColorOn.toHexString(), opacity: backgroundColorOn.getAlpha()},
    },
    off: {
      mainColor: {hex: mainColorOff.toHexString(), opacity: mainColorOff.getAlpha()},
      backgroundColor: {hex: backgroundColorOff.toHexString(), opacity: backgroundColorOff.getAlpha()},
    },
    disabled: {
      mainColor: {hex: mainColorDisabled.toHexString(), opacity: mainColorDisabled.getAlpha()},
      backgroundColor: {hex: backgroundColorDisabled.toHexString(), opacity: backgroundColorDisabled.getAlpha()},
    }
  };
};

export const powerButtonShapeSize = 110;
const cx = powerButtonShapeSize / 2;
const cy = powerButtonShapeSize / 2;

export abstract class PowerButtonShape {

  static fromSettings(ctx: WidgetContext,
                      svgShape: Svg,
                      settings: PowerButtonWidgetSettings,
                      value: boolean,
                      disabled: boolean,
                      onClick: () => void): PowerButtonShape {
    switch (settings.layout) {
      case PowerButtonLayout.default:
        return new DefaultPowerButtonShape(ctx, svgShape, settings, value, disabled, onClick);
      case PowerButtonLayout.simplified:
        return new SimplifiedPowerButtonShape(ctx, svgShape, settings, value, disabled, onClick);
      case PowerButtonLayout.outlined:
        return new OutlinedPowerButtonShape(ctx, svgShape, settings, value, disabled, onClick);
      case PowerButtonLayout.default_volume:
        return new DefaultVolumePowerButtonShape(ctx, svgShape, settings, value, disabled, onClick);
      case PowerButtonLayout.simplified_volume:
        return new SimplifiedVolumePowerButtonShape(ctx, svgShape, settings, value, disabled, onClick);
      case PowerButtonLayout.outlined_volume:
        return new OutlinedVolumePowerButtonShape(ctx, svgShape, settings, value, disabled, onClick);
    }
  }

  protected readonly colors: PowerButtonShapeColors;
  protected readonly onLabel: string;
  protected readonly offLabel: string;

  protected backgroundShape: Circle;
  protected hoverShape: Circle;
  protected hovered = false;
  protected pressed = false;
  protected forcePressed = false;

  protected constructor(protected widgetContext: WidgetContext,
                        protected svgShape: Svg,
                        protected settings: PowerButtonWidgetSettings,
                        protected value: boolean,
                        protected disabled: boolean,
                        protected onClick: () => void) {
    this.colors = createPowerButtonShapeColors(this.settings);
    this.onLabel = this.widgetContext.translate.instant('widgets.power-button.on-label').toUpperCase();
    this.offLabel = this.widgetContext.translate.instant('widgets.power-button.off-label').toUpperCase();
    this._drawShape();
  }

  public setValue(value: boolean) {
    if (this.value !== value) {
      this.value = value;
      this._drawState();
    }
  }

  public setDisabled(disabled: boolean) {
    if (this.disabled !== disabled) {
      this.disabled = disabled;
      this._drawState();
    }
  }

  public setPressed(pressed: boolean) {
    if (this.forcePressed !== pressed) {
      this.forcePressed = pressed;
      if (this.forcePressed && !this.pressed) {
        this.onPressStart();
      } else if (!this.forcePressed && !this.pressed) {
        this.onPressEnd();
      }
    }
  }

  private _drawShape() {

    this.backgroundShape = this.svgShape.circle(powerButtonShapeSize).center(cx, cy)
    .fill({opacity: 0}).stroke({width: 0});

    this.drawShape();

    this.hoverShape = this.svgShape.circle(powerButtonShapeSize).center(cx, cy).addClass('tb-hover-circle')
    .fill({color: '#000000', opacity: 0});
    this.hoverShape.on('mouseover', () => {
      this.hovered = true;
      if (!this.disabled) {
        this.hoverShape.timeline().finish();
        this.hoverShape.animate(200).attr({'fill-opacity': 0.06});
      }
    });
    this.hoverShape.on('mouseout', () => {
      this.hovered = false;
      this.hoverShape.timeline().finish();
      this.hoverShape.animate(200).attr({'fill-opacity': 0});
      this._cancelPressed();
    });
    this.hoverShape.on('touchmove', (event: TouchEvent) => {
      const touch = event.touches[0];
      const element = document.elementFromPoint(touch.pageX,touch.pageY);
      if (this.hoverShape.node !== element) {
        this._cancelPressed();
      }
    });
    this.hoverShape.on('touchcancel', () => {
      this._cancelPressed();
    });
    this.hoverShape.on('mousedown touchstart', (event: Event) => {
      if (event.type === 'mousedown') {
        if ((event as MouseEvent).button !== 0) {
          return;
        }
      }
      if (!this.disabled && !this.pressed) {
        this.pressed = true;
        if (!this.forcePressed) {
          this.onPressStart();
        }
      }
    });
    this.hoverShape.on('mouseup touchend touchcancel', () => {
      if (this.pressed && !this.disabled) {
        this.onClick();
      }
      this._cancelPressed();
    });
    this._drawState();
  }

  private _cancelPressed() {
    if (this.pressed) {
      this.pressed = false;
      if (!this.forcePressed) {
        this.onPressEnd();
      }
    }
  }

  private _drawState() {
    let colorState: PowerButtonColorState;
    if (this.disabled) {
      colorState = this.colors.disabled;
    } else {
      colorState = this.value ? this.colors.on : this.colors.off;
    }
    this.drawBackgroundState(colorState.backgroundColor);
    this.drawColorState(colorState.mainColor);
    if (this.value) {
      this.drawOn();
    } else {
      this.drawOff();
    }
    if (this.disabled) {
      this.hoverShape.timeline().finish();
      this.hoverShape.attr({'fill-opacity': 0});
    } else if (this.hovered) {
      this.hoverShape.timeline().finish();
      this.hoverShape.animate(200).attr({'fill-opacity': 0.06});
    }
  }

  private drawBackgroundState(backgroundColor: PowerButtonColor) {
    this.backgroundShape.attr({ fill: backgroundColor.hex, 'fill-opacity': backgroundColor.opacity});
  }

  protected drawShape() {}

  protected drawColorState(_mainColor: PowerButtonColor) {}

  protected drawOff() {}

  protected drawOn() {}

  protected onPressStart() {}

  protected onPressEnd() {}

  protected createMask(shape: Element, maskElements: Element[]) {
    const mask =
      this.svgShape.mask().add(this.svgShape.rect().width('100%').height('100%').fill('#fff'));
    maskElements.forEach(e => {
      mask.add(e.fill('#000').attr({'fill-opacity': 1}));
    });
    shape.maskWith(mask);
  }

  protected createPressedShadow(diameter: number, rightElseLeft = true): Gradient {
    const innerShadowGradient = this.svgShape.gradient('radial', (add) => {
      add.stop(0.7, '#000000', 0);
      add.stop(1, '#000000', 0.4);
    }).attr({ cx: rightElseLeft ? '45%' : '55%', cy: '55%', r: '100%'});
    this.svgShape.circle(diameter).center(cx, cy)
    .fill(innerShadowGradient).stroke({width: 0});
    return innerShadowGradient;
  }

  protected pressedAnimation(element: Element): Runner {
    return element.animate(200, 0, 'now');
  }

  protected createOnLabel(fontWeight = '500'): Text {
    return this.createLabel(this.onLabel, fontWeight);
  }

  protected createOffLabel(fontWeight = '500'): Text {
    return this.createLabel(this.offLabel, fontWeight);
  }

  private createLabel(text: string, fontWeight = '500'): Text {
    return this.svgShape.text(text).font({
      family: 'Roboto',
      weight: fontWeight,
      style: 'normal',
      size: '22px'
    }).attr({x: '50%', y: '50%', 'text-anchor': 'middle', 'dominant-baseline': 'middle'});
  }

}

class DefaultPowerButtonShape extends PowerButtonShape {

  private outerBorder: Circle;
  private outerBorderMask: Circle;
  private offLabelShape: Text;
  private onCircleShape: Circle;
  private onLabelShape: Text;
  private pressedShadow: Gradient;
  private pressedTimeline: Timeline;
  private centerGroup: G;

  protected drawShape() {
    this.outerBorder = this.svgShape.circle(powerButtonShapeSize).center(cx, cy)
                       .fill({opacity: 0}).stroke({width: 0});
    this.outerBorderMask = this.svgShape.circle(powerButtonShapeSize - 20).center(cx, cy);
    this.createMask(this.outerBorder, [this.outerBorderMask]);
    this.centerGroup = this.svgShape.group();
    this.offLabelShape = this.createOffLabel().addTo(this.centerGroup);
    this.onCircleShape = this.svgShape.circle(powerButtonShapeSize - 20)
    .center(cx, cy);
    this.onLabelShape = this.createOnLabel();
    this.createMask(this.onCircleShape, [this.onLabelShape]);
    this.pressedShadow = this.createPressedShadow(powerButtonShapeSize - 20);

    this.pressedTimeline = new Timeline();
    this.centerGroup.timeline(this.pressedTimeline);
    this.onLabelShape.timeline(this.pressedTimeline);
    this.pressedShadow.timeline(this.pressedTimeline);
  }

  protected drawColorState(mainColor: PowerButtonColor) {
    this.outerBorder.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
    this.offLabelShape.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
    this.onCircleShape.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
  }

  protected drawOff() {
    this.outerBorderMask.radius((powerButtonShapeSize - 20)/2);
    this.onCircleShape.hide();
    this.centerGroup.show();
  }

  protected drawOn() {
    this.outerBorderMask.radius((powerButtonShapeSize - 2)/2);
    this.centerGroup.hide();
    this.onCircleShape.show();
  }

  protected onPressStart() {
    this.pressedTimeline.finish();
    const pressedScale = 0.75;
    this.pressedAnimation(this.centerGroup).transform({scale: pressedScale});
    this.pressedAnimation(this.onLabelShape).transform({scale: pressedScale});
    this.pressedAnimation(this.pressedShadow).attr({r: '62%'});
  }

  protected onPressEnd() {
    this.pressedTimeline.finish();
    this.pressedAnimation(this.centerGroup).transform({scale: 1});
    this.pressedAnimation(this.onLabelShape).transform({scale: 1});
    this.pressedAnimation(this.pressedShadow).attr({r: '100%'});
  }

}

class SimplifiedPowerButtonShape extends PowerButtonShape {

  private outerBorder: Circle;
  private outerBorderMask: Circle;
  private onCircleShape: Circle;
  private offLabelShape: Text;
  private onLabelShape: Text;
  private pressedShadow: Gradient;
  private pressedTimeline: Timeline;
  private centerGroup: G;

  protected drawShape() {
    this.outerBorder = this.svgShape.circle(powerButtonShapeSize).center(cx, cy)
    .fill({opacity: 0}).stroke({width: 0});
    this.outerBorderMask = this.svgShape.circle(powerButtonShapeSize - 4).center(cx, cy);
    this.createMask(this.outerBorder, [this.outerBorderMask]);
    this.centerGroup = this.svgShape.group();
    this.offLabelShape = this.createOffLabel().addTo(this.centerGroup);
    this.onCircleShape = this.svgShape.circle(powerButtonShapeSize).center(cx, cy);
    this.onLabelShape = this.createOnLabel();
    this.createMask(this.onCircleShape, [this.onLabelShape]);
    this.pressedShadow = this.createPressedShadow(powerButtonShapeSize - 4);

    this.pressedTimeline = new Timeline();
    this.centerGroup.timeline(this.pressedTimeline);
    this.onLabelShape.timeline(this.pressedTimeline);
    this.pressedShadow.timeline(this.pressedTimeline);
  }

  protected drawColorState(mainColor: PowerButtonColor) {
    this.outerBorder.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
    this.onCircleShape.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
    this.offLabelShape.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
  }

  protected drawOff() {
    this.onCircleShape.hide();
    this.outerBorder.show();
    this.centerGroup.show();
  }

  protected drawOn() {
    this.centerGroup.hide();
    this.outerBorder.hide();
    this.onCircleShape.show();
  }

  protected onPressStart() {
    this.pressedTimeline.finish();
    const pressedScale = 0.75;
    this.pressedAnimation(this.centerGroup).transform({scale: pressedScale});
    this.pressedAnimation(this.onLabelShape).transform({scale: pressedScale});
    this.pressedAnimation(this.pressedShadow).attr({r: '62%'});
  }

  protected onPressEnd() {
    this.pressedTimeline.finish();
    this.pressedAnimation(this.centerGroup).transform({scale: 1});
    this.pressedAnimation(this.onLabelShape).transform({scale: 1});
    this.pressedAnimation(this.pressedShadow).attr({r: '100%'});
  }
}

class OutlinedPowerButtonShape extends PowerButtonShape {
  private outerBorder: Circle;
  private outerBorderMask: Circle;
  private innerBorder: Circle;
  private innerBorderMask: Circle;
  private offLabelShape: Text;
  private onCircleShape: Circle;
  private onLabelShape: Text;
  private pressedShadow: Gradient;
  private pressedTimeline: Timeline;
  private centerGroup: G;
  private onCenterGroup: G;

  protected drawShape() {
    this.outerBorder = this.svgShape.circle(powerButtonShapeSize).center(cx, cy)
    .fill({opacity: 0}).stroke({width: 0});
    this.outerBorderMask = this.svgShape.circle(powerButtonShapeSize - 2).center(cx, cy);
    this.createMask(this.outerBorder, [this.outerBorderMask]);
    this.innerBorder = this.svgShape.circle(powerButtonShapeSize - 20).center(cx, cy)
    .fill({opacity: 0}).stroke({width: 0});
    this.innerBorderMask = this.svgShape.circle(powerButtonShapeSize - 24).center(cx, cy);
    this.createMask(this.innerBorder, [this.innerBorderMask]);
    this.centerGroup = this.svgShape.group();
    this.offLabelShape = this.createOffLabel().addTo(this.centerGroup);
    this.onCenterGroup = this.svgShape.group();
    this.onCircleShape = this.svgShape.circle(powerButtonShapeSize - 28).center(cx, cy)
    .addTo(this.onCenterGroup);
    this.onLabelShape = this.createOnLabel();
    this.createMask(this.onCircleShape, [this.onLabelShape]);
    this.pressedShadow = this.createPressedShadow(powerButtonShapeSize - 24);

    this.pressedTimeline = new Timeline();
    this.centerGroup.timeline(this.pressedTimeline);
    this.onCenterGroup.timeline(this.pressedTimeline);
    this.onLabelShape.timeline(this.pressedTimeline);
    this.pressedShadow.timeline(this.pressedTimeline);
  }

  protected drawColorState(mainColor: PowerButtonColor) {
    this.outerBorder.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
    this.innerBorder.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
    this.offLabelShape.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
    this.onCircleShape.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
  }

  protected drawOff() {
    this.onCenterGroup.hide();
    this.centerGroup.show();
  }

  protected drawOn() {
    this.centerGroup.hide();
    this.onCenterGroup.show();
  }

  protected onPressStart() {
    this.pressedTimeline.finish();
    const pressedScale = 0.75;
    this.pressedAnimation(this.centerGroup).transform({scale: pressedScale});
    this.pressedAnimation(this.onCenterGroup).transform({scale: 0.98});
    this.pressedAnimation(this.onLabelShape).transform({scale: pressedScale / 0.98});
    this.pressedAnimation(this.pressedShadow).attr({r: '62%'});
  }

  protected onPressEnd() {
    this.pressedTimeline.finish();
    this.pressedAnimation(this.centerGroup).transform({scale: 1});
    this.pressedAnimation(this.onCenterGroup).transform({scale: 1});
    this.pressedAnimation(this.onLabelShape).transform({scale: 1});
    this.pressedAnimation(this.pressedShadow).attr({r: '100%'});
  }
}

class DefaultVolumePowerButtonShape extends PowerButtonShape {
  private outerBorder: Circle;
  private outerBorderMask: Circle;
  private outerBorderGradient: Gradient;
  private innerBorder: Circle;
  private innerBorderMask: Circle;
  private innerBorderGradient: Gradient;
  private innerShadow: Circle;
  private innerShadowGradient: Gradient;
  private innerShadowGradientStop: Stop;
  private offLabelShape: Text;
  private onCircleShape: Circle;
  private onLabelShape: Text;
  private pressedTimeline: Timeline;
  private centerGroup: G;

  protected drawShape() {
    this.outerBorder = this.svgShape.circle(powerButtonShapeSize).center(cx, cy)
    .fill({opacity: 0}).stroke({width: 0});
    this.outerBorderMask = this.svgShape.circle(powerButtonShapeSize - 20).center(cx, cy);
    this.createMask(this.outerBorder, [this.outerBorderMask]);
    this.outerBorderGradient = this.svgShape.gradient('linear', (add) => {
      add.stop(0, '#CCCCCC', 1);
      add.stop(1, '#FFFFFF', 1);
    }).from(0.268, 0.92).to(0.832, 0.1188);
    this.innerBorder = this.svgShape.circle(powerButtonShapeSize - 20).center(cx, cy)
    .fill({opacity: 0}).stroke({width: 0});
    this.innerBorderMask = this.svgShape.circle(powerButtonShapeSize - 24).center(cx, cy);
    this.createMask(this.innerBorder, [this.innerBorderMask]);
    this.innerBorderGradient = this.svgShape.gradient('linear', (add) => {
      add.stop(0, '#CCCCCC', 1);
      add.stop(1, '#FFFFFF', 1);
    }).from(0.832, 0.1188).to(0.268, 0.92);
    this.centerGroup = this.svgShape.group();
    this.offLabelShape = this.createOffLabel('400').addTo(this.centerGroup);
    this.onCircleShape = this.svgShape.circle(powerButtonShapeSize - 24).center(cx, cy);
    this.onLabelShape = this.createOnLabel('400');
    this.createMask(this.onCircleShape, [this.onLabelShape]);
    this.innerShadow = this.svgShape.circle(powerButtonShapeSize - 24).center(cx, cy);
    this.innerShadowGradient = this.svgShape.gradient('radial', (add) => {
      add.stop(0.7, '#000000', 0);
      this.innerShadowGradientStop = add.stop(1, '#000000', 0.2);
    }).attr({ cx: '45%', cy: '55%', r: '65%'});
    this.innerShadow.fill(this.innerShadowGradient);

    this.pressedTimeline = new Timeline();
    this.centerGroup.timeline(this.pressedTimeline);
    this.onLabelShape.timeline(this.pressedTimeline);
    this.innerShadowGradient.timeline(this.pressedTimeline);
    this.innerShadowGradientStop.timeline(this.pressedTimeline);
  }

  protected drawColorState(mainColor: PowerButtonColor){
    if (this.disabled) {
      this.backgroundShape.removeClass('tb-small-shadow');
      if (!this.forcePressed) {
        this.innerShadow.hide();
      }
      this.outerBorder.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
      this.innerBorder.attr({fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
    } else {
      this.backgroundShape.addClass('tb-small-shadow');
      this.innerShadow.show();
      this.outerBorder.fill(this.outerBorderGradient);
      this.outerBorder.attr({ 'fill-opacity': 1 });
      this.innerBorder.fill(this.innerBorderGradient);
      this.innerBorder.attr({ 'fill-opacity': 1 });
    }
    this.offLabelShape.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
    this.onCircleShape.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
  }

  protected drawOff() {
    this.onCircleShape.hide();
    this.centerGroup.show();
    this.innerBorder.show();
  }

  protected drawOn() {
    if (this.disabled) {
      this.innerBorder.hide();
    } else {
      this.innerBorder.show();
    }
    this.centerGroup.hide();
    this.onCircleShape.show();
  }

  protected onPressStart() {
    this.pressedTimeline.finish();
    this.innerShadow.show();
    const pressedScale = 0.75;
    this.pressedAnimation(this.centerGroup).transform({scale: pressedScale});
    this.pressedAnimation(this.onLabelShape).transform({scale: pressedScale});
    this.pressedAnimation(this.innerShadowGradient).attr({ r: '60%'});
    this.pressedAnimation(this.innerShadowGradientStop).update({ opacity: 0.4 });
  }

  protected onPressEnd() {
    this.pressedTimeline.finish();
    this.pressedAnimation(this.centerGroup).transform({scale: 1});
    this.pressedAnimation(this.onLabelShape).transform({scale: 1});
    this.pressedAnimation(this.innerShadowGradient).attr({ r: '65%'}).after(() => {
      if (this.disabled) {
        this.innerShadow.hide();
      }
    });
    this.pressedAnimation(this.innerShadowGradientStop).update({ opacity: 0.2 });
  }

}

class SimplifiedVolumePowerButtonShape extends PowerButtonShape {

  private outerBorder: Circle;
  private outerBorderMask: Circle;
  private offLabelShape: Text;
  private onLabelShape: Text;
  private innerShadow: Circle;
  private pressedShadow: Gradient;
  private pressedTimeline: Timeline;
  private centerGroup: G;
  private onCenterGroup: G;


  protected drawShape() {
    this.outerBorder = this.svgShape.circle(powerButtonShapeSize).center(cx, cy)
    .fill({color: '#FAFAFA', opacity: 1}).stroke({width: 0});
    this.outerBorderMask = this.svgShape.circle(powerButtonShapeSize - 4).center(cx, cy);
    this.createMask(this.outerBorder, [this.outerBorderMask]);
    this.centerGroup = this.svgShape.group();
    this.offLabelShape = this.createOffLabel().addTo(this.centerGroup);
    this.onCenterGroup = this.svgShape.group();
    this.onLabelShape = this.createOnLabel().addTo(this.onCenterGroup);
    this.innerShadow = this.svgShape.circle(powerButtonShapeSize - 4).center(cx, cy);
    const innerShadowGradient = this.svgShape.gradient('radial', (add) => {
      add.stop(0.7, '#000000', 0);
      add.stop(1, '#000000', 0.2);
    }).attr({ cx: '55%', cy: '55%', r: '65%'});
    this.innerShadow.fill(innerShadowGradient);
    this.pressedShadow = this.createPressedShadow(powerButtonShapeSize - 4, false);

    this.pressedTimeline = new Timeline();
    this.centerGroup.timeline(this.pressedTimeline);
    this.onCenterGroup.timeline(this.pressedTimeline);
    this.pressedShadow.timeline(this.pressedTimeline);
  }

  protected drawColorState(mainColor: PowerButtonColor){
    this.offLabelShape.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
    this.onLabelShape.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
  }

  protected drawOff() {
    if (!this.pressed) {
      this.backgroundShape.addClass('tb-shadow');
    }
    this.innerShadow.hide();
    this.onCenterGroup.hide();
    this.centerGroup.show();
  }

  protected drawOn() {
    this.backgroundShape.removeClass('tb-shadow');
    this.centerGroup.hide();
    this.onCenterGroup.show();
    this.innerShadow.show();
  }

  protected onPressStart() {
    this.pressedTimeline.finish();
    const pressedScale = 0.75;
    if (!this.value) {
      this.backgroundShape.removeClass('tb-shadow');
    }
    this.pressedAnimation(this.centerGroup).transform({scale: pressedScale});
    this.pressedAnimation(this.onCenterGroup).transform({scale: pressedScale});
    this.pressedAnimation(this.pressedShadow).attr({r: '62%'});
  }

  protected onPressEnd() {
    this.pressedTimeline.finish();
    this.pressedAnimation(this.centerGroup).transform({scale: 1});
    this.pressedAnimation(this.onCenterGroup).transform({scale: 1});
    this.pressedAnimation(this.pressedShadow).attr({r: '100%'}).after(() => {
      if (!this.value) {
        this.backgroundShape.addClass('tb-shadow');
      }
    });
  }
}

class OutlinedVolumePowerButtonShape extends PowerButtonShape {
  private outerBorder: Circle;
  private outerBorderMask: Circle;
  private outerBorderGradient: Gradient;
  private innerBorder: Circle;
  private innerBorderMask: Circle;
  private offLabelShape: Text;
  private onCircleShape: Circle;
  private onLabelShape: Text;
  private pressedShadow: Gradient;
  private pressedTimeline: Timeline;
  private centerGroup: G;
  private onCenterGroup: G;

  protected drawShape() {
    this.outerBorder = this.svgShape.circle(powerButtonShapeSize).center(cx, cy)
    .fill({opacity: 0}).stroke({width: 0});
    this.outerBorderMask = this.svgShape.circle(powerButtonShapeSize - 20).center(cx, cy);
    this.createMask(this.outerBorder, [this.outerBorderMask]);
    this.outerBorderGradient = this.svgShape.gradient('linear', (add) => {
      add.stop(0, '#CCCCCC', 1);
      add.stop(1, '#FFFFFF', 1);
    }).from(0.268, 0.92).to(0.832, 0.1188);
    this.innerBorder = this.svgShape.circle(powerButtonShapeSize - 20).center(cx, cy)
    .fill({opacity: 0}).stroke({width: 0});
    this.innerBorderMask = this.svgShape.circle(powerButtonShapeSize - 30).center(cx, cy);
    this.createMask(this.innerBorder, [this.innerBorderMask]);
    this.centerGroup = this.svgShape.group();
    this.offLabelShape = this.createOffLabel('800').addTo(this.centerGroup);
    this.onCenterGroup = this.svgShape.group();
    this.onCircleShape = this.svgShape.circle(powerButtonShapeSize - 30).center(cx, cy)
    .addTo(this.onCenterGroup);
    this.onLabelShape = this.createOnLabel('800');
    this.createMask(this.onCircleShape, [this.onLabelShape]);
    this.pressedShadow = this.createPressedShadow(powerButtonShapeSize - 30);
    this.backgroundShape.addClass('tb-small-shadow');

    this.pressedTimeline = new Timeline();
    this.centerGroup.timeline(this.pressedTimeline);
    this.onCenterGroup.timeline(this.pressedTimeline);
    this.onLabelShape.timeline(this.pressedTimeline);
    this.pressedShadow.timeline(this.pressedTimeline);
  }

  protected drawColorState(mainColor: PowerButtonColor){
    if (this.disabled) {
      this.outerBorder.attr({ fill: '#000000', 'fill-opacity': 0.03});
    } else {
      this.outerBorder.fill(this.outerBorderGradient);
      this.outerBorder.attr({ 'fill-opacity': 1 });
    }
    this.innerBorder.attr({fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
    this.offLabelShape.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
    this.onCircleShape.attr({ fill: mainColor.hex, 'fill-opacity': mainColor.opacity});
  }

  protected drawOff() {
    this.onCenterGroup.hide();
    this.centerGroup.show();
    this.innerBorder.show();
  }

  protected drawOn() {
    this.innerBorder.hide();
    this.centerGroup.hide();
    this.onCenterGroup.show();
  }

  protected onPressStart() {
    this.pressedTimeline.finish();
    const pressedScale = 0.75;
    this.pressedAnimation(this.centerGroup).transform({scale: pressedScale});
    this.pressedAnimation(this.onCenterGroup).transform({scale: 0.98});
    this.pressedAnimation(this.onLabelShape).transform({scale: pressedScale / 0.98});
    this.pressedAnimation(this.pressedShadow).attr({r: '62%'});
  }

  protected onPressEnd() {
    this.pressedTimeline.finish();
    this.pressedAnimation(this.centerGroup).transform({scale: 1});
    this.pressedAnimation(this.onCenterGroup).transform({scale: 1});
    this.pressedAnimation(this.onLabelShape).transform({scale: 1});
    this.pressedAnimation(this.pressedShadow).attr({r: '100%'});
  }

}