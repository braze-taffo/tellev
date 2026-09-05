import _ from 'lodash';
import $ from 'jquery';
import toastr from 'toastr';
import * as Vue from 'vue';
import * as z from 'zod';
import * as YAML from 'yaml';
import ejs from 'ejs/ejs.min.js';
Object.assign(window, { _, $, jQuery: $, toastr, Vue, z, YAML, ejs });
