precision mediump float;
varying vec4 v_Color;
varying float v_Discard;
void main() {
    if (v_Discard > 0.5) discard;
    gl_FragColor = v_Color;
}
